package net.suncaper.ten.basic.matching

import org.apache.spark.sql.execution.datasources.hbase.HBaseTableCatalog
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

class Gender {

  def catalogGenderRead =
    s"""{
       |"table":{"namespace":"default", "name":"tbl_users"},
       |"rowkey":"id",
       |"columns":{
       |"id":{"cf":"rowkey", "col":"id", "type":"string"},
       |"gender":{"cf":"cf", "col":"gender", "type":"string"}
       |}
       |}""".stripMargin

  def catalogGenderWrite =
    s"""{
       |"table":{"namespace":"default", "name":"aft_basic_user"},
       |"rowkey":"id",
       |"columns":{
       |"id":{"cf":"rowkey", "col":"id", "type":"string"},
       |"gender":{"cf":"user", "col":"gender", "type":"string"}
       |}
       |}""".stripMargin

  def finalWrite =
    s"""{
       |"table":{"namespace":"default", "name":"final"},
       |"rowkey":"gender",
       |"columns":{
       |"gender":{"cf":"rowkey", "col":"gender", "type":"string"},
       |"number":{"cf":"cf", "col":"val", "type":"string"}
       |}
       |}""".stripMargin

  val spark = SparkSession.builder()
    .appName("gender")
    .master("local[10]")
    .getOrCreate()

  import spark.implicits._

  val readDF: DataFrame = spark.read
    .option(HBaseTableCatalog.tableCatalog, catalogGenderRead)
    .format("org.apache.spark.sql.execution.datasources.hbase")
    .load()

  val genderW = readDF.select('id,
    when('gender === "1", "男")
      .when('gender === "2", "女")
      .otherwise("未知")
      .as("gender")
  )
  val finalGenderW = genderW
    .select('id,'gender)
    .groupBy('gender)
    .count()
    .withColumn("number",format_number('count,0))
    .drop('count)
  def genderWrite = {

    readDF.show()
    genderW.show()
    finalGenderW.show()
    try{

      genderW.write
        .option(HBaseTableCatalog.tableCatalog, catalogGenderWrite)
        .option(HBaseTableCatalog.newTable, "5")
        .format("org.apache.spark.sql.execution.datasources.hbase")
        .save()

    }catch {

      case ex: IllegalArgumentException =>

    }finally{

      println("genderWrite finish")

    }

    try{

      finalGenderW.write
        .option(HBaseTableCatalog.tableCatalog, finalWrite)
        .option(HBaseTableCatalog.newTable, "5")
        .format("org.apache.spark.sql.execution.datasources.hbase")
        .save()
    }catch {

      case ex: IllegalArgumentException =>

    }finally{

      println("genderFinalWrite finish")

    }

    spark.close()

  }

}
