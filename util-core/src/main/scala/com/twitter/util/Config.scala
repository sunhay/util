/*
 * Copyright 2011 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.util

import scala.collection.mutable.ListBuffer

/**
 * You can import Config._ if you want the auto-conversions in a class
 * that does not inherit from trait Config.
 */
object Config {
  sealed trait Required[+A] {
    def value: A
  }

  case class Specified[A](value: A) extends Required[A]

  case object Unspecified extends Required[scala.Nothing] {
    def value = throw new NoSuchElementException
  }

  class RequiredValuesMissing(names: Seq[String]) extends Exception(names.mkString(","))

  /**
   * Config classes that don't produce anything via an apply() method
   * can extends Config.Nothing, and still get access to the Required
   * classes and methods.
   */
  class Nothing extends Config[scala.Nothing] {
    def apply() = throw new UnsupportedOperationException
  }

  implicit def toSpecified[A](value: A) = Specified(value)
  implicit def toSpecifiedOption[A](value: A) = Specified(Some(value))
  implicit def fromRequired[A](req: Required[A]) = req.value
  implicit def intoOption[A](item: A): Option[A] = Some(item)
  implicit def fromOption[A](item: Option[A]): A = item.get
  implicit def intoList[A](item: A): List[A] = List(item)
}

/**
 * A config object contains vars for configuring an object of type T, and an apply() method
 * which turns this config into an object of type T.
 *
 * The trait also contains a few useful implicits.
 *
 * You can define fields that are required but that don't have a default value with:
 *     class BotConfig extends Config[Bot] {
 *       var botName = required[String]
 *       var botPort = required[Int]
 *       ....
 *     }
 */
trait Config[T] extends (() => T) {
  import Config.{Required, Specified, Unspecified, RequiredValuesMissing}

  def required[A]: Required[A] = Unspecified
  implicit def toSpecified[A](value: A) = Specified(value)
  implicit def toSpecifiedOption[A](value: A) = Specified(Some(value))
  implicit def fromRequired[A](req: Required[A]) = req.value
  implicit def intoOption[A](item: A): Option[A] = Some(item)
  implicit def fromOption[A](item: Option[A]): A = item.get
  implicit def intoList[A](item: A): List[A] = List(item)

  /**
   * Looks for any fields that are Required but currently Unspecified, in
   * this object and in any child Config objects, and returns the names
   * of those missing values.  For missing values in child Config objects,
   * the name is the dot-separated path down to that value.
   */
  def missingValues: Seq[String] = {
    val interestingReturnTypes = Seq(classOf[Required[_]], classOf[Config[_]])
    val buf = new ListBuffer[String]
    def collect(prefix: String, config: Config[_]) {
      val nullaryMethods = config.getClass.getMethods.toSeq filter { _.getParameterTypes.isEmpty }
      for (method <- nullaryMethods) {
        val name = method.getName
        val rt = method.getReturnType
        if (name != "required" &&
            !name.endsWith("$outer") && // no loops!
            interestingReturnTypes.exists(_.isAssignableFrom(rt))) {
          method.invoke(config) match {
            case Unspecified =>
              buf += (prefix + name)
            case Specified(sub: Config[_]) =>
              collect(prefix + name + ".", sub)
            case sub: Config[_] =>
              collect(prefix + name + ".", sub)
            case _ =>
          }
        }
      }
    }
    collect("", this)
    buf.toList
  }

  /**
   * @throws RequiredValuesMissing if there are any missing values.
   */
  def validate() {
    val missing = missingValues
    if (!missing.isEmpty) throw new RequiredValuesMissing(missing)
  }
}