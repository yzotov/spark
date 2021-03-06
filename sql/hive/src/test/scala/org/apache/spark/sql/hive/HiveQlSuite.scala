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

package org.apache.spark.sql.hive

import org.apache.hadoop.hive.serde.serdeConstants
import org.scalatest.BeforeAndAfterAll

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.catalog.{CatalogColumn, CatalogTable, CatalogTableType}
import org.apache.spark.sql.catalyst.expressions.JsonTuple
import org.apache.spark.sql.catalyst.parser.SimpleParserConf
import org.apache.spark.sql.catalyst.plans.logical.Generate

class HiveQlSuite extends SparkFunSuite with BeforeAndAfterAll {
  val parser = new HiveQl(SimpleParserConf())

  private def extractTableDesc(sql: String): (CatalogTable, Boolean) = {
    parser.parsePlan(sql).collect {
      case CreateTableAsSelect(desc, child, allowExisting) => (desc, allowExisting)
    }.head
  }

  test("Test CTAS #1") {
    val s1 =
      """CREATE EXTERNAL TABLE IF NOT EXISTS mydb.page_view
        |(viewTime INT,
        |userid BIGINT,
        |page_url STRING,
        |referrer_url STRING,
        |ip STRING COMMENT 'IP Address of the User',
        |country STRING COMMENT 'country of origination')
        |COMMENT 'This is the staging page view table'
        |PARTITIONED BY (dt STRING COMMENT 'date type', hour STRING COMMENT 'hour of the day')
        |ROW FORMAT DELIMITED FIELDS TERMINATED BY '\054' STORED AS RCFILE
        |LOCATION '/user/external/page_view'
        |TBLPROPERTIES ('p1'='v1', 'p2'='v2')
        |AS SELECT * FROM src""".stripMargin

    val (desc, exists) = extractTableDesc(s1)
    assert(exists)
    assert(desc.specifiedDatabase == Some("mydb"))
    assert(desc.name == "page_view")
    assert(desc.tableType == CatalogTableType.EXTERNAL_TABLE)
    assert(desc.storage.locationUri == Some("/user/external/page_view"))
    assert(desc.schema ==
      CatalogColumn("viewtime", "int") ::
      CatalogColumn("userid", "bigint") ::
      CatalogColumn("page_url", "string") ::
      CatalogColumn("referrer_url", "string") ::
      CatalogColumn("ip", "string", comment = Some("IP Address of the User")) ::
      CatalogColumn("country", "string", comment = Some("country of origination")) :: Nil)
    // TODO will be SQLText
    assert(desc.viewText == Option("This is the staging page view table"))
    assert(desc.partitionColumns ==
      CatalogColumn("dt", "string", comment = Some("date type")) ::
      CatalogColumn("hour", "string", comment = Some("hour of the day")) :: Nil)
    assert(desc.storage.serdeProperties ==
      Map((serdeConstants.SERIALIZATION_FORMAT, "\u002C"), (serdeConstants.FIELD_DELIM, "\u002C")))
    assert(desc.storage.inputFormat == Some("org.apache.hadoop.hive.ql.io.RCFileInputFormat"))
    assert(desc.storage.outputFormat == Some("org.apache.hadoop.hive.ql.io.RCFileOutputFormat"))
    assert(desc.storage.serde ==
      Some("org.apache.hadoop.hive.serde2.columnar.LazyBinaryColumnarSerDe"))
    assert(desc.properties == Map(("p1", "v1"), ("p2", "v2")))
  }

  test("Test CTAS #2") {
    val s2 =
      """CREATE EXTERNAL TABLE IF NOT EXISTS mydb.page_view
        |(viewTime INT,
        |userid BIGINT,
        |page_url STRING,
        |referrer_url STRING,
        |ip STRING COMMENT 'IP Address of the User',
        |country STRING COMMENT 'country of origination')
        |COMMENT 'This is the staging page view table'
        |PARTITIONED BY (dt STRING COMMENT 'date type', hour STRING COMMENT 'hour of the day')
        |ROW FORMAT SERDE 'parquet.hive.serde.ParquetHiveSerDe'
        | STORED AS
        | INPUTFORMAT 'parquet.hive.DeprecatedParquetInputFormat'
        | OUTPUTFORMAT 'parquet.hive.DeprecatedParquetOutputFormat'
        |LOCATION '/user/external/page_view'
        |TBLPROPERTIES ('p1'='v1', 'p2'='v2')
        |AS SELECT * FROM src""".stripMargin

    val (desc, exists) = extractTableDesc(s2)
    assert(exists)
    assert(desc.specifiedDatabase == Some("mydb"))
    assert(desc.name == "page_view")
    assert(desc.tableType == CatalogTableType.EXTERNAL_TABLE)
    assert(desc.storage.locationUri == Some("/user/external/page_view"))
    assert(desc.schema ==
      CatalogColumn("viewtime", "int") ::
      CatalogColumn("userid", "bigint") ::
      CatalogColumn("page_url", "string") ::
      CatalogColumn("referrer_url", "string") ::
      CatalogColumn("ip", "string", comment = Some("IP Address of the User")) ::
      CatalogColumn("country", "string", comment = Some("country of origination")) :: Nil)
    // TODO will be SQLText
    assert(desc.viewText == Option("This is the staging page view table"))
    assert(desc.partitionColumns ==
      CatalogColumn("dt", "string", comment = Some("date type")) ::
      CatalogColumn("hour", "string", comment = Some("hour of the day")) :: Nil)
    assert(desc.storage.serdeProperties == Map())
    assert(desc.storage.inputFormat == Some("parquet.hive.DeprecatedParquetInputFormat"))
    assert(desc.storage.outputFormat == Some("parquet.hive.DeprecatedParquetOutputFormat"))
    assert(desc.storage.serde == Some("parquet.hive.serde.ParquetHiveSerDe"))
    assert(desc.properties == Map(("p1", "v1"), ("p2", "v2")))
  }

  test("Test CTAS #3") {
    val s3 = """CREATE TABLE page_view AS SELECT * FROM src"""
    val (desc, exists) = extractTableDesc(s3)
    assert(exists == false)
    assert(desc.specifiedDatabase == None)
    assert(desc.name == "page_view")
    assert(desc.tableType == CatalogTableType.MANAGED_TABLE)
    assert(desc.storage.locationUri == None)
    assert(desc.schema == Seq.empty[CatalogColumn])
    assert(desc.viewText == None) // TODO will be SQLText
    assert(desc.storage.serdeProperties == Map())
    assert(desc.storage.inputFormat == Some("org.apache.hadoop.mapred.TextInputFormat"))
    assert(desc.storage.outputFormat ==
      Some("org.apache.hadoop.hive.ql.io.IgnoreKeyTextOutputFormat"))
    assert(desc.storage.serde.isEmpty)
    assert(desc.properties == Map())
  }

  test("Test CTAS #4") {
    val s4 =
      """CREATE TABLE page_view
        |STORED BY 'storage.handler.class.name' AS SELECT * FROM src""".stripMargin
    intercept[AnalysisException] {
      extractTableDesc(s4)
    }
  }

  test("Test CTAS #5") {
    val s5 = """CREATE TABLE ctas2
               | ROW FORMAT SERDE "org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe"
               | WITH SERDEPROPERTIES("serde_p1"="p1","serde_p2"="p2")
               | STORED AS RCFile
               | TBLPROPERTIES("tbl_p1"="p11", "tbl_p2"="p22")
               | AS
               |   SELECT key, value
               |   FROM src
               |   ORDER BY key, value""".stripMargin
    val (desc, exists) = extractTableDesc(s5)
    assert(exists == false)
    assert(desc.specifiedDatabase == None)
    assert(desc.name == "ctas2")
    assert(desc.tableType == CatalogTableType.MANAGED_TABLE)
    assert(desc.storage.locationUri == None)
    assert(desc.schema == Seq.empty[CatalogColumn])
    assert(desc.viewText == None) // TODO will be SQLText
    assert(desc.storage.serdeProperties == Map(("serde_p1" -> "p1"), ("serde_p2" -> "p2")))
    assert(desc.storage.inputFormat == Some("org.apache.hadoop.hive.ql.io.RCFileInputFormat"))
    assert(desc.storage.outputFormat == Some("org.apache.hadoop.hive.ql.io.RCFileOutputFormat"))
    assert(desc.storage.serde == Some("org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe"))
    assert(desc.properties == Map(("tbl_p1" -> "p11"), ("tbl_p2" -> "p22")))
  }

  test("Invalid interval term should throw AnalysisException") {
    def assertError(sql: String, errorMessage: String): Unit = {
      val e = intercept[AnalysisException] {
        parser.parsePlan(sql)
      }
      assert(e.getMessage.contains(errorMessage))
    }
    assertError("select interval '42-32' year to month",
      "month 32 outside range [0, 11]")
    assertError("select interval '5 49:12:15' day to second",
      "hour 49 outside range [0, 23]")
    assertError("select interval '.1111111111' second",
      "nanosecond 1111111111 outside range")
  }

  test("use native json_tuple instead of hive's UDTF in LATERAL VIEW") {
    val plan = parser.parsePlan(
      """
        |SELECT *
        |FROM (SELECT '{"f1": "value1", "f2": 12}' json) test
        |LATERAL VIEW json_tuple(json, 'f1', 'f2') jt AS a, b
      """.stripMargin)

    assert(plan.children.head.asInstanceOf[Generate].generator.isInstanceOf[JsonTuple])
  }

  test("use backticks in output of Script Transform") {
    val plan = parser.parsePlan(
      """SELECT `t`.`thing1`
        |FROM (SELECT TRANSFORM (`parquet_t1`.`key`, `parquet_t1`.`value`)
        |USING 'cat' AS (`thing1` int, `thing2` string) FROM `default`.`parquet_t1`) AS t
      """.stripMargin)
  }
}
