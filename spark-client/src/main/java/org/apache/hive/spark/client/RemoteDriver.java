/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.spark.client;

import com.google.common.base.Throwables;
import com.google.common.io.Files;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hive.common.classification.InterfaceAudience;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hive.spark.client.metrics.Metrics;
import org.apache.hive.spark.client.rpc.Rpc;
import org.apache.hive.spark.client.rpc.RpcConfiguration;
import org.apache.hive.spark.counter.SparkCounters;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkException;
import org.apache.spark.SparkJobInfo;
import org.apache.spark.api.java.JavaFutureAction;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.scheduler.SparkListener;
import org.apache.spark.scheduler.SparkListenerJobEnd;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.SparkListenerTaskEnd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Tuple2;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Driver code for the Spark client library.
 */
@InterfaceAudience.Private
public class RemoteDriver {

  private static final Logger LOG = LoggerFactory.getLogger(RemoteDriver.class);

  private final Map<String, JobWrapper<?>> activeJobs;
  private final Object jcLock;
  private final Object shutdownLock;
  private final ExecutorService executor;
  private final NioEventLoopGroup egroup;
  private final Rpc clientRpc;
  private final DriverProtocol protocol;
  // a local temp dir specific to this driver
  private final File localTmpDir;

  // Used to queue up requests while the SparkContext is being created.
  private final List<JobWrapper<?>> jobQueue = Lists.newLinkedList();

  // jc is effectively final, but it has to be volatile since it's accessed by different
  // threads while the constructor is running.
  private volatile JobContextImpl jc;
  private volatile boolean running;

  public static final String REMOTE_DRIVER_HOST_CONF = "--remote-host";
  public static final String REMOTE_DRIVER_PORT_CONF = "--remote-port";
  public static final String REMOTE_DRIVER_CONF = "--remote-driver-conf";

  private final long futureTimeout; // Rpc call timeout in milliseconds

  private RemoteDriver(String[] args) throws Exception {
    this.activeJobs = Maps.newConcurrentMap();
    this.jcLock = new Object();
    this.shutdownLock = new Object();
    localTmpDir = Files.createTempDir();

    addShutdownHook();

    SparkConf conf = new SparkConf();
    String serverAddress = null;
    int serverPort = -1;
    Map<String, String> mapConf = Maps.newHashMap();
    for (int idx = 0; idx < args.length; idx += 2) {
      String key = args[idx];
      if (REMOTE_DRIVER_HOST_CONF.equals(key)) {
        serverAddress = getArg(args, idx);
      } else if (REMOTE_DRIVER_PORT_CONF.equals(key)) {
        serverPort = Integer.parseInt(getArg(args, idx));
      } else if (REMOTE_DRIVER_CONF.equals(key)) {
        String[] val = getArg(args, idx).split("[=]", 2);
        //set these only in mapConf and not in SparkConf,
        // as these are non-spark specific configs used by the remote driver
        mapConf.put(val[0], val[1]);
      } else {
        throw new IllegalArgumentException("Invalid command line arguments: "
          + Joiner.on(" ").join(args));
      }
    }

    executor = Executors.newCachedThreadPool();

    LOG.info("Connecting to HiveServer2 address: {}:{}", serverAddress, serverPort);

    for (Tuple2<String, String> e : conf.getAll()) {
      mapConf.put(e._1(), e._2());
      LOG.debug("Remote Spark Driver configured with: " + e._1() + "=" + e._2());
    }

    String clientId = mapConf.get(SparkClientFactory.CONF_CLIENT_ID);
    Preconditions.checkArgument(clientId != null, "No client ID provided.");
    String secret = mapConf.get(SparkClientFactory.CONF_KEY_SECRET);
    Preconditions.checkArgument(secret != null, "No secret provided.");

    RpcConfiguration rpcConf = new RpcConfiguration(mapConf);
    futureTimeout = rpcConf.getFutureTimeoutMs();
    int threadCount = rpcConf.getRpcThreadCount();
    this.egroup = new NioEventLoopGroup(
        threadCount,
        new ThreadFactoryBuilder()
            .setNameFormat("Spark-Driver-RPC-Handler-%d")
            .setDaemon(true)
            .build());
    this.protocol = new DriverProtocol();

    // The RPC library takes care of timing out this.
    this.clientRpc = Rpc.createClient(mapConf, egroup, serverAddress, serverPort,
      clientId, secret, protocol).get();
    this.running = true;

    this.clientRpc.addListener(new Rpc.Listener() {
      @Override
      public void rpcClosed(Rpc rpc) {
        LOG.warn("Shutting down driver because Remote Spark Driver to HiveServer2 connection was closed.");
        shutdown(null);
      }

      @Override
      public String toString() {
        return "Shutting Down Remote Spark Driver to HiveServer2 Connection";
      }
    });

    try {
      JavaSparkContext sc = new JavaSparkContext(conf);

      String sessionTimeoutInterval = conf.get(HiveConf.ConfVars.SPARK_SESSION_AM_DAG_SUBMIT_TIMEOUT_SECS.varname);
      if (sessionTimeoutInterval == null || Integer.valueOf(sessionTimeoutInterval) == 0) {
        sc.sc().addSparkListener(new ClientListener());
      } else {
        sc.sc().addSparkListener(new ClientListener(this, 1000*Integer.valueOf(sessionTimeoutInterval)));
      }
      sc.sc().addSparkListener(new ClientListener());
      synchronized (jcLock) {
        jc = new JobContextImpl(sc, localTmpDir);
        jcLock.notifyAll();
      }
    } catch (Exception e) {
      LOG.error("Failed to start SparkContext: " + e, e);
      shutdown(e);
      synchronized (jcLock) {
        jcLock.notifyAll();
      }
      throw e;
    }

    synchronized (jcLock) {
      for (Iterator<JobWrapper<?>> it = jobQueue.iterator(); it.hasNext();) {
        it.next().submit();
      }
    }
  }

  private void addShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (running) {
        LOG.info("Received signal SIGTERM, attempting safe shutdown of Remote Spark Context");
        protocol.sendErrorMessage("Remote Spark Context was shutdown because it received a SIGTERM " +
                "signal. Most likely due to a kill request via YARN.");
        shutdown(null);
      }
    }));
  }

  private void run() throws InterruptedException {
    synchronized (shutdownLock) {
      while (running) {
        shutdownLock.wait();
      }
    }
    executor.shutdownNow();
    try {
      FileUtils.deleteDirectory(localTmpDir);
    } catch (IOException e) {
      LOG.warn("Failed to delete local tmp dir: " + localTmpDir, e);
    }
  }

  private void submit(JobWrapper<?> job) {
    synchronized (jcLock) {
      if (jc != null) {
        job.submit();
      } else {
        LOG.info("SparkContext not yet up; adding Hive on Spark job request to the queue.");
        jobQueue.add(job);
      }
    }
  }

  private synchronized void shutdown(Throwable error) {
    if (running) {
      if (error == null) {
        LOG.info("Shutting down Spark Remote Driver.");
      } else {
        LOG.error("Shutting down Spark Remote Driver due to error: " + error, error);
      }
      running = false;
      for (JobWrapper<?> job : activeJobs.values()) {
        cancelJob(job);
      }

      if (error != null) {
        try {
          protocol.sendError(error).get(futureTimeout, TimeUnit.MILLISECONDS);
        } catch(InterruptedException|ExecutionException|TimeoutException e) {
          LOG.warn("Failed to send out the error during RemoteDriver shutdown", e);
        }
      }
      if (jc != null) {
        jc.stop();
      }
      clientRpc.close();

      egroup.shutdownGracefully();
      synchronized (shutdownLock) {
        shutdownLock.notifyAll();
      }
    }
  }

  private boolean cancelJob(JobWrapper<?> job) {
    boolean cancelled = false;
    for (JavaFutureAction<?> action : job.jobs) {
      cancelled |= action.cancel(true);
    }
    return cancelled | (job.future != null && job.future.cancel(true));
  }

  private String getArg(String[] args, int keyIdx) {
    int valIdx = keyIdx + 1;
    if (args.length <= valIdx) {
      throw new IllegalArgumentException("Invalid command line arguments: "
        + Joiner.on(" ").join(args));
    }
    return args[valIdx];
  }

  private class DriverProtocol extends BaseProtocol {

    Future<Void> sendError(Throwable error) {
      LOG.debug("Send error to Client: {}", Throwables.getStackTraceAsString(error));
      return clientRpc.call(new Error(Throwables.getStackTraceAsString(error)));
    }

    Future<Void> sendErrorMessage(String cause) {
      LOG.debug("Send error to Client: {}", cause);
      return clientRpc.call(new Error(cause));
    }

    <T extends Serializable>
    Future<Void> jobFinished(String jobId, T result,
        Throwable error, SparkCounters counters) {
      LOG.debug("Send job({}) result to Client.", jobId);
      return clientRpc.call(new JobResult<T>(jobId, result, error, counters));
    }

    Future<Void> jobStarted(String jobId) {
      return clientRpc.call(new JobStarted(jobId));
    }

    Future<Void> jobSubmitted(String jobId, int sparkJobId) {
      LOG.debug("Send job({}/{}) submitted to Client.", jobId, sparkJobId);
      return clientRpc.call(new JobSubmitted(jobId, sparkJobId));
    }

    Future<Void> sendMetrics(String jobId, int sparkJobId, int stageId, long taskId, Metrics metrics) {
      LOG.debug("Send task({}/{}/{}/{}) metric to Client.", jobId, sparkJobId, stageId, taskId);
      return clientRpc.call(new JobMetrics(jobId, sparkJobId, stageId, taskId, metrics));
    }

    private void handle(ChannelHandlerContext ctx, CancelJob msg) {
      JobWrapper<?> job = activeJobs.get(msg.id);
      if (job == null || !cancelJob(job)) {
        LOG.info("Requested to cancel an already finished client job.");
      }
    }

    private void handle(ChannelHandlerContext ctx, EndSession msg) {
      LOG.debug("Shutting down due to EndSession request.");
      shutdown(null);
    }

    private void handle(ChannelHandlerContext ctx, JobRequest msg) {
      LOG.debug("Received client job request {}", msg.id);
      JobWrapper<?> wrapper = new JobWrapper<Serializable>(msg);
      activeJobs.put(msg.id, wrapper);
      submit(wrapper);
    }

    private Object handle(ChannelHandlerContext ctx, SyncJobRequest msg) throws Exception {
      // In case the job context is not up yet, let's wait, since this is supposed to be a
      // "synchronous" RPC.
      if (jc == null) {
        synchronized (jcLock) {
          while (jc == null) {
            jcLock.wait();
            if (!running) {
              throw new IllegalStateException("Remote Spark context is shutting down.");
            }
          }
        }
      }

      jc.setMonitorCb(new MonitorCallback() {
        @Override
        public void call(JavaFutureAction<?> future,
            SparkCounters sparkCounters, Set<Integer> cachedRDDIds) {
          throw new IllegalStateException(
            "JobContext.monitor() is not available for synchronous jobs.");
        }
      });
      try {
        return msg.job.call(jc);
      } finally {
        jc.setMonitorCb(null);
      }
    }

    @Override
    public String name() {
      return "Remote Spark Driver to HiveServer2 Connection";
    }
  }

  private class JobWrapper<T extends Serializable> implements Callable<Void> {

    private final BaseProtocol.JobRequest<T> req;
    private final List<JavaFutureAction<?>> jobs;
    private final AtomicInteger jobEndReceived;
    private int completed;
    private SparkCounters sparkCounters;
    private Set<Integer> cachedRDDIds;
    private Integer sparkJobId;

    private Future<?> future;

    JobWrapper(BaseProtocol.JobRequest<T> req) {
      this.req = req;
      this.jobs = Lists.newArrayList();
      completed = 0;
      jobEndReceived = new AtomicInteger(0);
      this.sparkCounters = null;
      this.cachedRDDIds = null;
      this.sparkJobId = null;
    }

    @Override
    public Void call() throws Exception {
      protocol.jobStarted(req.id);

      try {
        jc.setMonitorCb(new MonitorCallback() {
          @Override
          public void call(JavaFutureAction<?> future,
              SparkCounters sparkCounters, Set<Integer> cachedRDDIds) {
            monitorJob(future, sparkCounters, cachedRDDIds);
          }
        });

        T result = req.job.call(jc);
        // In case the job is empty, there won't be JobStart/JobEnd events. The only way
        // to know if the job has finished is to check the futures here ourselves.
        for (JavaFutureAction<?> future : jobs) {
          future.get();
          completed++;
          LOG.debug("Client job {}: {} of {} Spark jobs finished.",
              req.id, completed, jobs.size());
        }

        // If the job is not empty (but runs fast), we have to wait until all the TaskEnd/JobEnd
        // events are processed. Otherwise, task metrics may get lost. See HIVE-13525.
        if (sparkJobId != null) {
          SparkJobInfo sparkJobInfo = jc.sc().statusTracker().getJobInfo(sparkJobId);
          if (sparkJobInfo != null && sparkJobInfo.stageIds() != null &&
              sparkJobInfo.stageIds().length > 0) {
            synchronized (jobEndReceived) {
              while (jobEndReceived.get() < jobs.size()) {
                jobEndReceived.wait();
              }
            }
          }
        }

        SparkCounters counters = null;
        if (sparkCounters != null) {
          counters = sparkCounters.snapshot();
        }

        protocol.jobFinished(req.id, result, null, counters);
      } catch (Throwable t) {
        // Catch throwables in a best-effort to report job status back to the client. It's
        // re-thrown so that the executor can destroy the affected thread (or the JVM can
        // die or whatever would happen if the throwable bubbled up).
        LOG.error("Failed to run client job " + req.id, t);
        protocol.jobFinished(req.id, null, t,
            sparkCounters != null ? sparkCounters.snapshot() : null);
        throw new ExecutionException(t);
      } finally {
        jc.setMonitorCb(null);
        activeJobs.remove(req.id);
        releaseCache();
      }
      return null;
    }

    void submit() {
      this.future = executor.submit(this);
    }

    void jobDone() {
      synchronized (jobEndReceived) {
        jobEndReceived.incrementAndGet();
        jobEndReceived.notifyAll();
      }
    }

    /**
     * Release cached RDDs as soon as the job is done.
     * This is different from local Spark client so as
     * to save a RPC call/trip, avoid passing cached RDD
     * id information around. Otherwise, we can follow
     * the local Spark client way to be consistent.
     */
    void releaseCache() {
      if (cachedRDDIds != null) {
        for (Integer cachedRDDId: cachedRDDIds) {
          jc.sc().sc().unpersistRDD(cachedRDDId, false);
        }
      }
    }

    private void monitorJob(JavaFutureAction<?> job,
        SparkCounters sparkCounters, Set<Integer> cachedRDDIds) {
      jobs.add(job);
      if (!jc.getMonitoredJobs().containsKey(req.id)) {
        jc.getMonitoredJobs().put(req.id, new CopyOnWriteArrayList<JavaFutureAction<?>>());
      }
      jc.getMonitoredJobs().get(req.id).add(job);
      this.sparkCounters = sparkCounters;
      this.cachedRDDIds = cachedRDDIds;
      sparkJobId = job.jobIds().get(0);
      protocol.jobSubmitted(req.id, sparkJobId);
    }

  }

  private class ClientListener extends SparkListener {

    private final Map<Integer, Integer> stageToJobId = Maps.newHashMap();
    private long sessionTimeoutInterval;
    private long lastDAGCompletionTime = System.currentTimeMillis();
    private RemoteDriver driver;

    ClientListener() {}

    ClientListener(RemoteDriver driver, long sessionTimeoutInterval) {
      this.sessionTimeoutInterval = sessionTimeoutInterval;
      this.driver = driver;
      new Timer("DAGSubmissionTimer", true).scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
          try {
            if (stageToJobId.size() == 0) {
              checkAndHandleSessionTimeout();
            } else {
              LOG.debug("Some job stages are still running, no need to check session timeout(stages size:" + stageToJobId.size() + ")");
            }
          } catch (SparkException e) {
            LOG.error("Error when checking AM session timeout", e);
          }
        }
      }, sessionTimeoutInterval, sessionTimeoutInterval / 10);
    }

    private synchronized void checkAndHandleSessionTimeout() throws SparkException {

      long currentTime = System.currentTimeMillis();
      if (currentTime < (lastDAGCompletionTime + sessionTimeoutInterval)) {
        return;
      }
      String message = "Session timed out"
              + ", lastDAGCompletionTime=" + lastDAGCompletionTime + " ms"
              + ", sessionTimeoutInterval=" + sessionTimeoutInterval + " ms";
      LOG.info(message);
      if (driver != null)
        driver.shutdown(null);
    }

    @Override
    public void onJobStart(SparkListenerJobStart jobStart) {
      synchronized (stageToJobId) {
        for (int i = 0; i < jobStart.stageIds().length(); i++) {
          stageToJobId.put((Integer) jobStart.stageIds().apply(i), jobStart.jobId());
        }
      }
    }

    @Override
    public void onJobEnd(SparkListenerJobEnd jobEnd) {
      synchronized (stageToJobId) {
        for (Iterator<Map.Entry<Integer, Integer>> it = stageToJobId.entrySet().iterator();
            it.hasNext();) {
          Map.Entry<Integer, Integer> e = it.next();
          if (e.getValue() == jobEnd.jobId()) {
            it.remove();
          }
        }
      }

      String clientId = getClientId(jobEnd.jobId());
      if (clientId != null) {
        activeJobs.get(clientId).jobDone();
      }
      lastDAGCompletionTime = System.currentTimeMillis();
    }

    @Override
    public void onTaskEnd(SparkListenerTaskEnd taskEnd) {
      if (taskEnd.reason() instanceof org.apache.spark.Success$
          && !taskEnd.taskInfo().speculative()) {
        Metrics metrics = new Metrics(taskEnd.taskMetrics(), taskEnd.taskInfo());
        Integer jobId;
        synchronized (stageToJobId) {
          jobId = stageToJobId.get(taskEnd.stageId());
        }

        // TODO: implement implicit AsyncRDDActions conversion instead of jc.monitor()?
        // TODO: how to handle stage failures?

        String clientId = getClientId(jobId);
        if (clientId != null) {
          protocol.sendMetrics(clientId, jobId, taskEnd.stageId(),
            taskEnd.taskInfo().taskId(), metrics);
        }
      }
    }

    /**
     * Returns the client job ID for the given Spark job ID.
     *
     * This will only work for jobs monitored via JobContext#monitor(). Other jobs won't be
     * matched, and this method will return `None`.
     */
    private String getClientId(Integer jobId) {
      for (Map.Entry<String, JobWrapper<?>> e : activeJobs.entrySet()) {
        for (JavaFutureAction<?> future : e.getValue().jobs) {
          if (future.jobIds().contains(jobId)) {
            return e.getKey();
          }
        }
      }
      return null;
    }

  }

  public static void main(String[] args) throws Exception {
    RemoteDriver rd = new RemoteDriver(args);
    try {
      rd.run();
    } catch (Exception e) {
      // If the main thread throws an exception for some reason, propagate the exception to the
      // client and initiate a safe shutdown
      if (rd.running) {
        rd.shutdown(e);
      }
      throw e;
    }
  }
}

