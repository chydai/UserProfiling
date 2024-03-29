package net.suncaper.ten.basic.mining

import org.apache.spark.ml.clustering.KMeans
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.sql.execution.datasources.hbase.HBaseTableCatalog
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{Column, DataFrame, SparkSession, functions}

class SpendingPowerRFM {

  def catalog =
    s"""{
       |  "table":{"namespace":"default", "name":"tbl_orders"},
       |  "rowkey":"id",
       |  "columns":{
       |    "id":{"cf":"rowkey", "col":"id", "type":"string"},
       |    "memberId":{"cf":"cf", "col":"memberId", "type":"string"},
       |    "orderSn":{"cf":"cf", "col":"orderSn", "type":"string"},
       |    "orderAmount":{"cf":"cf", "col":"orderAmount", "type":"string"},
       |    "finishTime":{"cf":"cf", "col":"finishTime", "type":"string"}
       |  }
       |}""".stripMargin

  def catalogWrite =
    s"""{
       |"table":{"namespace":"default", "name":"aft_basic_biz"},
       |"rowkey":"id",
       |"columns":{
       |  "id":{"cf":"rowkey", "col":"id", "type":"string"},
       |  "spendingPower":{"cf":"biz", "col":"spendingPower", "type":"string"}
       |}
       |}""".stripMargin

  val spark = SparkSession.builder()
    .appName("RFMModel")
    .master("local[10]")
    .getOrCreate()

  import spark.implicits._

  val source: DataFrame = spark.read
    .option(HBaseTableCatalog.tableCatalog, catalog)
    .format("org.apache.spark.sql.execution.datasources.hbase")
    .load()


  val colRencency = "rencency"
  val colFrequency = "frequency"
  val colMoneyTotal = "moneyTotal"
  val colFeature = "feature"
  val colPredict = "predict"
  val days_range = 660

  // 统计距离最近一次消费的时间
  val recencyCol = datediff(date_sub(current_timestamp(), days_range), from_unixtime(max('finishTime))) as colRencency
  // 统计订单总数
  val frequencyCol = count('orderSn) as colFrequency
  // 统计订单总金额
  val moneyTotalCol = sum('orderAmount) as colMoneyTotal

  val RFMResult = source.groupBy('memberId)
    .agg(recencyCol, frequencyCol, moneyTotalCol)

  //2.为RFM打分
  //R: 1-3天=5分，4-6天=4分，7-9天=3分，10-15天=2分，大于16天=1分
  //F: ≥200=5分，150-199=4分，100-149=3分，50-99=2分，1-49=1分
  //M: ≥20w=5分，10-19w=4分，5-9w=3分，1-4w=2分，<1w=1分
  val recencyScore: Column = functions.when((col(colRencency) >= 1) && (col(colRencency) <= 3), 5)
    .when((col(colRencency) >= 4) && (col(colRencency) <= 6), 4)
    .when((col(colRencency) >= 7) && (col(colRencency) <= 9), 3)
    .when((col(colRencency) >= 10) && (col(colRencency) <= 15), 2)
    .when(col(colRencency) >= 16, 1)
    .as(colRencency)

  val frequencyScore: Column = functions.when(col(colFrequency) >= 200, 5)
    .when((col(colFrequency) >= 150) && (col(colFrequency) <= 199), 4)
    .when((col(colFrequency) >= 100) && (col(colFrequency) <= 149), 3)
    .when((col(colFrequency) >= 50) && (col(colFrequency) <= 99), 2)
    .when((col(colFrequency) >= 1) && (col(colFrequency) <= 49), 1)
    .as(colFrequency)

  val moneyTotalScore: Column = functions.when(col(colMoneyTotal) >= 200000, 5)
    .when(col(colMoneyTotal).between(100000, 199999), 4)
    .when(col(colMoneyTotal).between(50000, 99999), 3)
    .when(col(colMoneyTotal).between(10000, 49999), 2)
    .when(col(colMoneyTotal) <= 9999, 1)
    .as(colMoneyTotal)

  val RFMScoreResult = RFMResult.select('memberId, recencyScore, frequencyScore, moneyTotalScore)

  val vectorDF = new VectorAssembler()
    .setInputCols(Array(colRencency, colFrequency, colMoneyTotal))
    .setOutputCol(colFeature)
    .transform(RFMScoreResult)

  val kmeans = new KMeans()
    .setK(7)
    .setSeed(1000)
    .setMaxIter(2)
    .setFeaturesCol(colFeature)
    .setPredictionCol(colPredict)

  // train model
  val model = kmeans.fit(vectorDF)



  val predicted = model.transform(vectorDF)

  val result = predicted.select('*,
    when('predict === "0", "超高")
      .when('predict === "1", "高")
      .when('predict === "2", "中上")
      .when('predict === "3", "中")
      .when('predict === "4", "中下")
      .when('predict === "5", "低")
      .when('predict === "6", "很低")
      .otherwise("其他")
      .as("spendingPower"))
    .drop('rencency).drop('frequency)
    .drop('moneyTotal).drop('feature).drop('predict)
    .withColumnRenamed("memberId", "id")

  //TODO: 将结果写入HBASE
  def spendingPowerWrite={

    //model.save("model/product/rfmmodel")

    predicted.show()
    result.show()

    try{

      result.write
        .option(HBaseTableCatalog.tableCatalog, catalogWrite)
        .option(HBaseTableCatalog.newTable, "5")
        .format("org.apache.spark.sql.execution.datasources.hbase")
        .save()

    }catch {

      case ex: IllegalArgumentException =>

    }finally{

      println("spendingPowerWrite finish")

    }



    spark.close()

  }

}
