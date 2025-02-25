/*
 * Copyright (c) 2024 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.spark.query

import com.couchbase.client.core.error.InvalidArgumentException
import com.couchbase.spark.util.{SparkOperationalSimpleTest, SparkOperationalTest}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.max
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotNull, assertThrows}
import org.junit.jupiter.api.Test
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._

class QueryPartitioningIntegrationTest extends SparkOperationalSimpleTest {
  override def testName: String = "QueryPartitioningIntegrationTest"

  @Test
  def missingOptionThrows(): Unit = {
    assertThrows(
      classOf[InvalidArgumentException],
      () => {
        spark.read
          .format("couchbase.query")
          .option(QueryOptions.PartitionLowerBound, "1")
          .option(QueryOptions.PartitionUpperBound, "8")
          .option(QueryOptions.PartitionCount, "2")
          .load()
      }
    )

    assertThrows(
      classOf[InvalidArgumentException],
      () => {
        spark.read
          .format("couchbase.query")
          .option(QueryOptions.PartitionColumn, "runways")
          .option(QueryOptions.PartitionUpperBound, "8")
          .option(QueryOptions.PartitionCount, "2")
          .load()
      }
    )

    assertThrows(
      classOf[InvalidArgumentException],
      () => {
        spark.read
          .format("couchbase.query")
          .option(QueryOptions.PartitionColumn, "runways")
          .option(QueryOptions.PartitionLowerBound, "1")
          .option(QueryOptions.PartitionCount, "2")
          .load()
      }
    )

    assertThrows(
      classOf[InvalidArgumentException],
      () => {
        spark.read
          .format("couchbase.query")
          .option(QueryOptions.PartitionColumn, "runways")
          .option(QueryOptions.PartitionLowerBound, "1")
          .option(QueryOptions.PartitionUpperBound, "8")
          .load()
      })
  }

  @Test
  def basic(): Unit = {
    val airports = spark.read
      .format("couchbase.query")
      .option(QueryOptions.Scope, testResources.scopeName)
      .option(QueryOptions.Collection, testResources.collectionName)
      .option(QueryOptions.ScanConsistency, QueryOptions.RequestPlusScanConsistency)
      .load()

    print(airports)
    assertEquals(4, airports.count)
  }

  @Test
  def testReadDocumentsFromCollection(): Unit = {
    assertEquals(4, readDocumentsFromCollectionHelper(1, 8, 2))
  }

  @Test
  def groupByCountAggregationIsCorrect(): Unit = {
    val groups = partitionedQuery(1, 8, 2).groupBy("country").count().collect()
    assertEquals(3, groups.length)
    assertEquals(2L, groups.find(v => v.get(0) == "United States").get(1))
    assertEquals(1L, groups.find(v => v.get(0) == "Germany").get(1))
  }

  @Test
  def groupByMaxAggregationIsCorrect(): Unit = {
    val groups = partitionedQuery(1, 8, 2).groupBy("country").agg(max("elevation")).collect()
    assertEquals(3, groups.length)
    assertEquals(204L, groups.find(v => v.get(0) == "United States").get(1))
    assertEquals(111L, groups.find(v => v.get(0) == "Germany").get(1))
  }

  @Test
  def maxAggregationIsCorrect(): Unit = {
    val value = partitionedQuery(1, 8, 2).agg(max("elevation")).collect()
    assertEquals(1, value.length)
    assertEquals(204L, value(0).get(0))
  }

  @Test
  def minAggregationIsCorrect(): Unit = {
    val value = partitionedQuery(1, 8, 2).agg(min("elevation")).collect()
    assertEquals(1, value.length)
    assertEquals(4L, value(0).get(0))
  }

  @Test
  def countIsCorrect(): Unit = {
    val value = partitionedQuery(1, 8, 2).count()
    assertEquals(4L, value)
  }

  @Test
  def countAggregationIsCorrect(): Unit = {
    val value = partitionedQuery(1, 8, 2).agg(count("elevation")).collect()
    assertEquals(1, value.length)
    assertEquals(4L, value(0).get(0))
  }

  @Test
  def countStarAggregationIsCorrect(): Unit = {
    val value = partitionedQuery(1, 8, 2).agg(count("*")).collect()
    assertEquals(1, value.length)
    assertEquals(4L, value(0).get(0))
  }

  @Test
  def sumAggregationIsCorrect(): Unit = {
    val value = partitionedQuery(1, 8, 2).agg(sum("elevation")).collect()
    assertEquals(1, value.length)
    assertEquals(204L + 4L + 183L + 111L, value(0).get(0))
  }

  @Test
  def limit(): Unit = {
    val value = partitionedQuery(1, 8, 2).limit(2).collect()
    assertEquals(2, value.length)
  }

  @Test
  def countMoreThanRange(): Unit = {
    assertEquals(4, readDocumentsFromCollectionHelper(1, 8, 9))
  }

  // Should result in all rows in first partition
  @Test
  def lowerAboveLastValue(): Unit = {
    assertEquals(4, readDocumentsFromCollectionHelper(1000000, 2000000, 2))
  }

  @Test
  def zeroPartitions(): Unit = {
    assertThrows(
      classOf[InvalidArgumentException],
      () => {
        assertEquals(0, readDocumentsFromCollectionHelper(0, 10, 0))
      })
  }

  @Test
  def lowerBoundEqualsUpperBound(): Unit = {
    assertThrows(
      classOf[InvalidArgumentException],
      () => {
        assertEquals(0, readDocumentsFromCollectionHelper(10, 10, 10))
      })
  }

  @Test
  def lowerBoundOverUpperBound(): Unit = {
    assertThrows(
      classOf[InvalidArgumentException],
      () => {
        assertEquals(0, readDocumentsFromCollectionHelper(11, 10, 10))
      })
  }

  def partitionedQuery(lowerBound: Long, upperBound: Long, partitionCount: Long): DataFrame = {
    spark.read
      .format("couchbase.query")
      .option(QueryOptions.Scope, testResources.scopeName)
      .option(QueryOptions.Collection, testResources.collectionName)
      .option(QueryOptions.ScanConsistency, QueryOptions.RequestPlusScanConsistency)
      .option(QueryOptions.PartitionColumn, "runways")
      .option(QueryOptions.PartitionLowerBound, lowerBound.toString)
      .option(QueryOptions.PartitionUpperBound, upperBound.toString)
      .option(QueryOptions.PartitionCount, partitionCount.toString)
      .load()
  }

  def readDocumentsFromCollectionHelper(lowerBound: Long, upperBound: Long, partitionCount: Long): Long = {
    val airports = partitionedQuery(lowerBound, upperBound, partitionCount)

    val ap = airports.collect()

    ap.foreach(row => {
      assertNotNull(row.getAs[String]("__META_ID"))
      assertNotNull(row.getAs[String]("name"))
    })
    ap.size
  }
}
