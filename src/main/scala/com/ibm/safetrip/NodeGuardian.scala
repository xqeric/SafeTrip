/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ibm.safetrip

import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor._
import akka.cluster.Cluster
import akka.pattern.gracefulStop
import org.apache.spark.streaming.kafka.KafkaInputDStream
import org.apache.spark.streaming.StreamingContext


/**
 * The `NodeGuardian` is the root of the application and manages a set of
 * [[AggregationActor]] actors.
 *
 * Creates the [[KafkaStreamingActor]] which
 *    - Transforms raw weather data .gz files
 *    to line data and publishes to the Kafka topic created in [[com.databricks.apps.WeatherApp]].
 *    - Creates a streaming pipeline from Kafka to Cassandra,
 *    via Spark, which streams the raw data from Kafka, transforms each line of data to
 *    a [[com.databricks.apps.weather.Weather.RawWeatherData]] (hourly per weather station),
 *    and saves the new data to the cassandra raw data table as it arrives.
 */
class NodeGuardian(ssc: StreamingContext,
                   settings: SafeTripSettings)
  extends ClusterAware with AggregationActor with ActorLogging {

  import settings._
  import OnTimeEvent._

  /* Creates the Kafka actors: */

  val kafks = context.actorOf(Props(new KafkaStreamingActor(ssc, settings, self,"time-raw-data")), "kafka-stream")
  log.info(kafks.toString)
  /* The Spark/Cassandra computation actors: For the tutorial we just use 2005 for now. */
  /*val temperature = context.actorOf(Props(new TemperatureActor(ssc.sparkContext, settings)), "temperature")
  val precipitation = context.actorOf(Props(new PrecipitationActor(ssc, settings)), "precipitation")
  val station = context.actorOf(Props(new WeatherStationActor(ssc.sparkContext, settings)), "weather-station")*/

  override def preStart(): Unit = {
    log.info("Starting at {}", cluster.selfAddress)
    cluster.joinSeedNodes(immutable.Seq(self.path.address))
  }

  override def postStop(): Unit = {
    log.info("Node {} shutting down.", cluster.selfAddress)
    cluster.leave(self.path.address)
  }

  /** On startup, actor is in an [[uninitialized]] state. */
  override def receive = uninitialized orElse initialized orElse super.receive

  /** When [[OutputStreamInitialized]] is received from the [[KafkaStreamingActor]] after
    * it creates and defines the [[KafkaInputDStream]], at which point the streaming
    * checkpoint can be set, the [[StreamingContext]] can be started, and the actor
    * moves from [[uninitialized]] to [[initialized]]with [[ActorContext.become()]].
    */
  def uninitialized: Actor.Receive = {
    case OutputStreamInitialized => initialize()
  }

  def initialized: Actor.Receive = {
    /*case e: KafkaMessageEnvelope[_,_] =>
      log.debug("Forwarding request {} to {}", e, publisher)
      publisher forward e
    case e: TemperatureRequest =>
      log.debug("Forwarding request {} to to {}", e, temperature)
      temperature forward e
    case e: PrecipitationRequest =>
      log.debug("Forwarding request {} to to {}", e, precipitation)
      precipitation forward e
    case e: WeatherStationRequest =>
      log.debug("Forwarding request {} to to {}", e, station)
      station forward e*/
    case PoisonPill =>
      gracefulShutdown()
  }

  def initialize(): Unit = {
    log.info(s"Node is transitioning from 'uninitialized' to 'initialized'")
    ssc.checkpoint(SparkCheckpointDir)
    ssc.start() // currently can not add more dstreams once started

    context become initialized
    context.system.eventStream.publish(NodeInitialized(self))
  }

  def gracefulShutdown(): Unit = {
    //context.children foreach (c => awaitCond(gracefulStop(c, timeout.duration).isCompleted))
    log.info(s"Graceful stop completed.")
  }

}

class ClusterAware extends Actor with ActorLogging {
  import akka.cluster.ClusterEvent._

  val cluster = Cluster(context.system)

  override def preStart(): Unit =
    cluster.subscribe(self, classOf[ClusterDomainEvent])

  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive : Actor.Receive = {
    case MemberUp(member) =>
      log.info("Member is Up: {}", member.address)
    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}",
        member.address, previousStatus)
    case _: MemberEvent => // ignore
  }
}