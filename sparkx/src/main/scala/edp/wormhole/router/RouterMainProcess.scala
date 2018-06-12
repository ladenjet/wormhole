/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.wormhole.router

import java.util.UUID

import edp.wormhole.common.util.DateUtils
import edp.wormhole.common.{FeedbackPriority, SparkUtils, WormholeConfig, WormholeUtils}
import edp.wormhole.kafka.WormholeKafkaProducer
import edp.wormhole.memorystorage.ConfMemoryStorage
import edp.wormhole.spark.log.EdpLogging
import edp.wormhole.ums.{UmsFeedbackStatus, UmsProtocolType, UmsProtocolUtils}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.kafka010.{CanCommitOffsets, HasOffsetRanges, OffsetRange, WormholeDirectKafkaInputDStream}

import scala.collection.mutable.ArrayBuffer

object RouterMainProcess extends EdpLogging {

  def process(stream: WormholeDirectKafkaInputDStream[String, String], config: WormholeConfig, session: SparkSession): Unit = {
    stream.foreachRDD((streamRdd: RDD[ConsumerRecord[String, String]]) => {
      val startTime = System.currentTimeMillis()
      val offsetInfo: ArrayBuffer[OffsetRange] = new ArrayBuffer[OffsetRange]
      val batchId = UUID.randomUUID().toString
      streamRdd.asInstanceOf[HasOffsetRanges].offsetRanges.copyToBuffer(offsetInfo)
      try {
        logInfo("start foreachRDD")
        if (SparkUtils.isLocalMode(config.spark_config.master)) logWarning("rdd count ===> " + streamRdd.count())

        //        val rddTs = System.currentTimeMillis

        logInfo("start doDirectiveTopic")
        //        val directiveTs = System.currentTimeMillis
        RouterDirective.doDirectiveTopic(config, stream)

        logInfo("start Repartition")
        //        val mainDataTs = System.currentTimeMillis

        val routerKeys = ConfMemoryStorage.getRouterKeys

        val dataRepartitionRdd: RDD[(String, String)] =
          if (config.rdd_partition_number != -1) streamRdd.map(row => {
            if(row.key==null||row.key.trim.isEmpty){
              val realNamespace = WormholeUtils.getFieldContentFromJson(row.value,"namespace")
              (realNamespace,row.value)
            }else (row.key, row.value)
          }).repartition(config.rdd_partition_number)
          else streamRdd.map(row => (row.key, row.value))
        dataRepartitionRdd.cache()

        val routerMap = ConfMemoryStorage.getRouterMap

        val allCount = dataRepartitionRdd.count()

        dataRepartitionRdd.foreachPartition { partition =>
          routerMap.foreach { case (_, (map, _)) =>
            map.foreach { case (_, (kafkaBroker, _)) => {
              WormholeKafkaProducer.init(kafkaBroker, None)
            }
            }
          }
          partition.foreach { case (key, value) => {
            val keys = key.split("\\.")
            val (protocolType, namespace) = if (keys.length > 7) (keys(0).toLowerCase, keys.slice(1, 8).mkString(".")) else (keys(0).toLowerCase, "")
            val matchNamespace = (namespace.split("\\.").take(4).mkString(".") + ".*.*.*").toLowerCase()
            if (ConfMemoryStorage.existNamespace(routerKeys, matchNamespace)) {
              if (routerMap(matchNamespace)._2 == "ums") {
                logInfo("start process namespace: " + matchNamespace)
                val messageIndex = value.lastIndexOf(namespace)
                val prefix = value.substring(0, messageIndex)
                val suffix = value.substring(messageIndex + namespace.length)
                routerMap(matchNamespace)._1.foreach { case (sinkNamespace, (kafkaBroker, kafkaTopic)) =>
                  val messageBuf = new StringBuilder
                  messageBuf ++= prefix ++= sinkNamespace ++= suffix
                  val kafkaMessage = messageBuf.toString
                  WormholeKafkaProducer.sendMessage(kafkaTopic, kafkaMessage, Some(protocolType + "." + sinkNamespace + "..." + UUID.randomUUID().toString), kafkaBroker)
                }
              } else {
                routerMap(matchNamespace)._1.foreach { case (sinkNamespace, (kafkaBroker, kafkaTopic)) =>
                  WormholeKafkaProducer.sendMessage(kafkaTopic, value, Some(protocolType + "." + sinkNamespace + "..." + UUID.randomUUID().toString), kafkaBroker)
                }
              }
            }
          }
          }
        }

        dataRepartitionRdd.unpersist()

        val endTime = System.currentTimeMillis()
        WormholeKafkaProducer.sendMessage(config.kafka_output.feedback_topic_name, FeedbackPriority.FeedbackPriority4,
          UmsProtocolUtils.feedbackFlowStats("*.*.*.*.*.*.*", UmsProtocolType.DATA_INCREMENT_DATA.toString, DateUtils.currentDateTime, config.spark_config.stream_id, batchId, "kafka.*.*.*.*.*.*",
            allCount.toInt, startTime, startTime, startTime, startTime, startTime, startTime, endTime), None, config.kafka_output.brokers)


        WormholeUtils.sendTopicPartitionOffset(offsetInfo, config.kafka_output.feedback_topic_name, config, batchId)
      } catch {
        case e: Throwable =>
          logAlert("batch error", e)
          WormholeKafkaProducer.sendMessage(config.kafka_output.feedback_topic_name, FeedbackPriority.FeedbackPriority3, UmsProtocolUtils.feedbackStreamBatchError(config.spark_config.stream_id, DateUtils.currentDateTime, UmsFeedbackStatus.SUCCESS, e.getMessage, batchId), None, config.kafka_output.brokers)
          WormholeUtils.sendTopicPartitionOffset(offsetInfo, config.kafka_output.feedback_topic_name, config, batchId)
      }
      stream.asInstanceOf[CanCommitOffsets].commitAsync(offsetInfo.toArray)
    })
  }

  def removeFromRouterMap(sourceNamespace: String, sinkNamespace: String): Unit = {
    synchronized {
      if (ConfMemoryStorage.routerMap.contains(sourceNamespace) && ConfMemoryStorage.routerMap(sourceNamespace)._1.contains(sinkNamespace)) {
        ConfMemoryStorage.routerMap(sourceNamespace)._1.remove(sinkNamespace)
        if (ConfMemoryStorage.routerMap(sourceNamespace)._1.isEmpty) {
          ConfMemoryStorage.routerMap.remove(sourceNamespace)
        }
      } else {
        logAlert("router from " + sourceNamespace + " to " + sinkNamespace + " does not exist")
      }
    }
  }
}