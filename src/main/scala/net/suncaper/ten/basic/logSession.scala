package net.suncaper.ten.basic

import org.apache.spark.sql.execution.datasources.hbase.HBaseTableCatalog
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._

class LogSession {

  def catalog =
    s"""{
       |"table":{"namespace":"default", "name":"tbl_users"},
       |"rowkey":"id",
       |"columns":{
       |"id":{"cf":"rowkey", "col":"id", "type":"string"},
       |"lastLoginTime":{"cf":"cf", "col":"lastLoginTime", "type":"string"}
       |}
       |}""".stripMargin

  def catalogWrite =
    s"""{
       |"table":{"namespace":"default", "name":"aft_basic_beh"},
       |"rowkey":"id",
       |"columns":{
       |"id":{"cf":"rowkey", "col":"id", "type":"string"},
       |"LogTime":{"cf":"behavior", "col":"LogTime", "type":"string"},
       |"logSession":{"cf":"behavior", "col":"logSession", "type":"string"}
       |}
       |}""".stripMargin

  val spark = SparkSession.builder()
    .appName("shc test")
    .master("local[10]")
    .getOrCreate()

  import spark.implicits._

  val readDF: DataFrame = spark.read
    .option(HBaseTableCatalog.tableCatalog, catalog)
    .format("org.apache.spark.sql.execution.datasources.hbase")
    .load()
 // val recencyCol = datediff(date_sub(current_timestamp(), 660), from_unixtime(max('finishTime))) as "temp"

  val logSessionW = readDF
    .select('id,from_unixtime('lastLoginTime) as 'LogTime)
      .select('id,'LogTime,
        when(hour($"LogTime") < 8 && hour($"LogTime") > 0, "0-7点")
        .when(hour($"LogTime") < 13 && hour($"LogTime") > 7, "8-12点")
        .when(hour($"LogTime") < 18 && hour($"LogTime") > 12, "13-17点")
        .when(hour($"LogTime") < 22 && hour($"LogTime") > 17, "18-21点")
        .when(hour($"LogTime") < 24 && hour($"LogTime") > 21, "22-24点")
        .as("logSession"))


  def logSessionWrite={
    readDF.show()
    logSessionW.show()

    logSessionW.write
      .option(HBaseTableCatalog.tableCatalog, catalogWrite)
      .option(HBaseTableCatalog.newTable, "5")
      .format("org.apache.spark.sql.execution.datasources.hbase")
      .save()

    spark.close()
  }

}