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

package org.apache.kyuubi.engine.spark.schema

import java.nio.ByteBuffer

import scala.collection.JavaConverters._

import org.apache.spark.sql.Row
import org.apache.spark.sql.execution.HiveResult
import org.apache.spark.sql.execution.HiveResult.TimeFormatters
import org.apache.spark.sql.types._

import org.apache.kyuubi.shaded.hive.service.rpc.thrift._
import org.apache.kyuubi.util.RowSetUtils._

object RowSet {

  def toHiveString(
      valueAndType: (Any, DataType),
      nested: Boolean = false,
      timeFormatters: TimeFormatters): String = {
    HiveResult.toHiveString(valueAndType, nested, timeFormatters)
  }

  def toTRowSet(
      bytes: Array[Byte],
      protocolVersion: TProtocolVersion): TRowSet = {
    if (protocolVersion.getValue < TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V6.getValue) {
      throw new UnsupportedOperationException
    } else {
      toColumnBasedSet(bytes)
    }
  }

  def emptyTRowSet(): TRowSet = {
    new TRowSet(0, new java.util.ArrayList[TRow](0))
  }

  def toColumnBasedSet(data: Array[Byte]): TRowSet = {
    val tRowSet = new TRowSet(0, new java.util.ArrayList[TRow](1))
    val tColumn = toTColumn(data)
    tRowSet.addToColumns(tColumn)
    tRowSet
  }

  def toTRowSet(
      rows: Seq[Row],
      schema: StructType,
      protocolVersion: TProtocolVersion): TRowSet = {
    if (protocolVersion.getValue < TProtocolVersion.HIVE_CLI_SERVICE_PROTOCOL_V6.getValue) {
      toRowBasedSet(rows, schema)
    } else {
      toColumnBasedSet(rows, schema)
    }
  }

  def toRowBasedSet(rows: Seq[Row], schema: StructType): TRowSet = {
    val rowSize = rows.length
    val tRows = new java.util.ArrayList[TRow](rowSize)
    val timeFormatters = HiveResult.getTimeFormatters
    var i = 0
    while (i < rowSize) {
      val row = rows(i)
      val tRow = new TRow()
      var j = 0
      val columnSize = row.length
      while (j < columnSize) {
        val columnValue = toTColumnValue(j, row, schema, timeFormatters)
        tRow.addToColVals(columnValue)
        j += 1
      }
      i += 1
      tRows.add(tRow)
    }
    new TRowSet(0, tRows)
  }

  def toColumnBasedSet(rows: Seq[Row], schema: StructType): TRowSet = {
    val rowSize = rows.length
    val tRowSet = new TRowSet(0, new java.util.ArrayList[TRow](rowSize))
    val timeFormatters = HiveResult.getTimeFormatters
    var i = 0
    val columnSize = schema.length
    while (i < columnSize) {
      val field = schema(i)
      val tColumn = toTColumn(rows, i, field.dataType, timeFormatters)
      tRowSet.addToColumns(tColumn)
      i += 1
    }
    tRowSet
  }

  private def toTColumn(
      rows: Seq[Row],
      ordinal: Int,
      typ: DataType,
      timeFormatters: TimeFormatters): TColumn = {
    val nulls = new java.util.BitSet()
    typ match {
      case BooleanType =>
        val values = getOrSetAsNull[java.lang.Boolean](rows, ordinal, nulls, true)
        TColumn.boolVal(new TBoolColumn(values, nulls))

      case ByteType =>
        val values = getOrSetAsNull[java.lang.Byte](rows, ordinal, nulls, 0.toByte)
        TColumn.byteVal(new TByteColumn(values, nulls))

      case ShortType =>
        val values = getOrSetAsNull[java.lang.Short](rows, ordinal, nulls, 0.toShort)
        TColumn.i16Val(new TI16Column(values, nulls))

      case IntegerType =>
        val values = getOrSetAsNull[java.lang.Integer](rows, ordinal, nulls, 0)
        TColumn.i32Val(new TI32Column(values, nulls))

      case LongType =>
        val values = getOrSetAsNull[java.lang.Long](rows, ordinal, nulls, 0L)
        TColumn.i64Val(new TI64Column(values, nulls))

      case FloatType =>
        val values = getOrSetAsNull[java.lang.Float](rows, ordinal, nulls, 0.toFloat)
          .asScala.map(n => java.lang.Double.valueOf(n.toString)).asJava
        TColumn.doubleVal(new TDoubleColumn(values, nulls))

      case DoubleType =>
        val values = getOrSetAsNull[java.lang.Double](rows, ordinal, nulls, 0.toDouble)
        TColumn.doubleVal(new TDoubleColumn(values, nulls))

      case StringType =>
        val values = getOrSetAsNull[java.lang.String](rows, ordinal, nulls, "")
        TColumn.stringVal(new TStringColumn(values, nulls))

      case BinaryType =>
        val values = getOrSetAsNull[Array[Byte]](rows, ordinal, nulls, Array())
          .asScala
          .map(ByteBuffer.wrap)
          .asJava
        TColumn.binaryVal(new TBinaryColumn(values, nulls))

      case _ =>
        var i = 0
        val rowSize = rows.length
        val values = new java.util.ArrayList[String](rowSize)
        while (i < rowSize) {
          val row = rows(i)
          nulls.set(i, row.isNullAt(ordinal))
          values.add(toHiveString(row.get(ordinal) -> typ, timeFormatters = timeFormatters))
          i += 1
        }
        TColumn.stringVal(new TStringColumn(values, nulls))
    }
  }

  private def getOrSetAsNull[T](
      rows: Seq[Row],
      ordinal: Int,
      nulls: java.util.BitSet,
      defaultVal: T): java.util.List[T] = {
    val size = rows.length
    val ret = new java.util.ArrayList[T](size)
    var idx = 0
    while (idx < size) {
      val row = rows(idx)
      val isNull = row.isNullAt(ordinal)
      if (isNull) {
        nulls.set(idx, true)
        ret.add(idx, defaultVal)
      } else {
        ret.add(idx, row.getAs[T](ordinal))
      }
      idx += 1
    }
    ret
  }

  private def toTColumnValue(
      ordinal: Int,
      row: Row,
      types: StructType,
      timeFormatters: TimeFormatters): TColumnValue = {
    types(ordinal).dataType match {
      case BooleanType =>
        val boolValue = new TBoolValue
        if (!row.isNullAt(ordinal)) boolValue.setValue(row.getBoolean(ordinal))
        TColumnValue.boolVal(boolValue)

      case ByteType =>
        val byteValue = new TByteValue
        if (!row.isNullAt(ordinal)) byteValue.setValue(row.getByte(ordinal))
        TColumnValue.byteVal(byteValue)

      case ShortType =>
        val tI16Value = new TI16Value
        if (!row.isNullAt(ordinal)) tI16Value.setValue(row.getShort(ordinal))
        TColumnValue.i16Val(tI16Value)

      case IntegerType =>
        val tI32Value = new TI32Value
        if (!row.isNullAt(ordinal)) tI32Value.setValue(row.getInt(ordinal))
        TColumnValue.i32Val(tI32Value)

      case LongType =>
        val tI64Value = new TI64Value
        if (!row.isNullAt(ordinal)) tI64Value.setValue(row.getLong(ordinal))
        TColumnValue.i64Val(tI64Value)

      case FloatType =>
        val tDoubleValue = new TDoubleValue
        if (!row.isNullAt(ordinal)) {
          val doubleValue = java.lang.Double.valueOf(row.getFloat(ordinal).toString)
          tDoubleValue.setValue(doubleValue)
        }
        TColumnValue.doubleVal(tDoubleValue)

      case DoubleType =>
        val tDoubleValue = new TDoubleValue
        if (!row.isNullAt(ordinal)) tDoubleValue.setValue(row.getDouble(ordinal))
        TColumnValue.doubleVal(tDoubleValue)

      case StringType =>
        val tStringValue = new TStringValue
        if (!row.isNullAt(ordinal)) tStringValue.setValue(row.getString(ordinal))
        TColumnValue.stringVal(tStringValue)

      case _ =>
        val tStrValue = new TStringValue
        if (!row.isNullAt(ordinal)) {
          tStrValue.setValue(toHiveString(
            row.get(ordinal) -> types(ordinal).dataType,
            timeFormatters = timeFormatters))
        }
        TColumnValue.stringVal(tStrValue)
    }
  }

  private def toTColumn(data: Array[Byte]): TColumn = {
    val values = new java.util.ArrayList[ByteBuffer](1)
    values.add(ByteBuffer.wrap(data))
    val nulls = new java.util.BitSet()
    TColumn.binaryVal(new TBinaryColumn(values, nulls))
  }
}
