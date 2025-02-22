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

package org.apache.spark.sql.jdbc

import java.sql.{SQLException, Types}
import java.util.Locale

import scala.util.control.NonFatal

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.{NoSuchNamespaceException, NoSuchTableException, TableAlreadyExistsException}
import org.apache.spark.sql.connector.expressions.Expression
import org.apache.spark.sql.connector.expressions.aggregate.{AggregateFunc, GeneralAggregateFunc}
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils
import org.apache.spark.sql.types.{BooleanType, ByteType, DataType, DecimalType, ShortType, StringType}

private object H2Dialect extends JdbcDialect {
  override def canHandle(url: String): Boolean =
    url.toLowerCase(Locale.ROOT).startsWith("jdbc:h2")

  class H2SQLBuilder extends JDBCSQLBuilder {
    override def visitSQLFunction(funcName: String, inputs: Array[String]): String = {
      funcName match {
        case "WIDTH_BUCKET" =>
          val functionInfo = super.visitSQLFunction(funcName, inputs)
          throw QueryCompilationErrors.noSuchFunctionError("H2", functionInfo)
        case _ => super.visitSQLFunction(funcName, inputs)
      }
    }
  }

  override def compileExpression(expr: Expression): Option[String] = {
    val h2SQLBuilder = new H2SQLBuilder()
    try {
      Some(h2SQLBuilder.build(expr))
    } catch {
      case NonFatal(e) =>
        logWarning("Error occurs while compiling V2 expression", e)
        None
    }
  }

  override def compileAggregate(aggFunction: AggregateFunc): Option[String] = {
    super.compileAggregate(aggFunction).orElse(
      aggFunction match {
        case f: GeneralAggregateFunc if f.name() == "VAR_POP" =>
          assert(f.children().length == 1)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"VAR_POP($distinct${f.children().head})")
        case f: GeneralAggregateFunc if f.name() == "VAR_SAMP" =>
          assert(f.children().length == 1)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"VAR_SAMP($distinct${f.children().head})")
        case f: GeneralAggregateFunc if f.name() == "STDDEV_POP" =>
          assert(f.children().length == 1)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"STDDEV_POP($distinct${f.children().head})")
        case f: GeneralAggregateFunc if f.name() == "STDDEV_SAMP" =>
          assert(f.children().length == 1)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"STDDEV_SAMP($distinct${f.children().head})")
        case f: GeneralAggregateFunc if f.name() == "COVAR_POP" =>
          assert(f.children().length == 2)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"COVAR_POP($distinct${f.children().head}, ${f.children().last})")
        case f: GeneralAggregateFunc if f.name() == "COVAR_SAMP" =>
          assert(f.children().length == 2)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"COVAR_SAMP($distinct${f.children().head}, ${f.children().last})")
        case f: GeneralAggregateFunc if f.name() == "CORR" =>
          assert(f.children().length == 2)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"CORR($distinct${f.children().head}, ${f.children().last})")
        case _ => None
      }
    )
  }

  override def getJDBCType(dt: DataType): Option[JdbcType] = dt match {
    case StringType => Option(JdbcType("CLOB", Types.CLOB))
    case BooleanType => Some(JdbcType("BOOLEAN", Types.BOOLEAN))
    case ShortType | ByteType => Some(JdbcType("SMALLINT", Types.SMALLINT))
    case t: DecimalType => Some(
      JdbcType(s"NUMERIC(${t.precision},${t.scale})", Types.NUMERIC))
    case _ => JdbcUtils.getCommonJDBCType(dt)
  }

  override def classifyException(message: String, e: Throwable): AnalysisException = {
    e match {
      case exception: SQLException =>
        // Error codes are from https://www.h2database.com/javadoc/org/h2/api/ErrorCode.html
        exception.getErrorCode match {
          // TABLE_OR_VIEW_ALREADY_EXISTS_1
          case 42101 =>
            throw new TableAlreadyExistsException(message, cause = Some(e))
          // TABLE_OR_VIEW_NOT_FOUND_1
          case 42102 =>
            throw NoSuchTableException(message, cause = Some(e))
          // SCHEMA_NOT_FOUND_1
          case 90079 =>
            throw NoSuchNamespaceException(message, cause = Some(e))
          case _ => // do nothing
        }
      case _ => // do nothing
    }
    super.classifyException(message, e)
  }
}
