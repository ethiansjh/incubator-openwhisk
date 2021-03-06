/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.controller.test

import java.time.{Clock, Instant}

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._
import whisk.core.controller.WhiskActivationsApi
import whisk.core.database.ArtifactStoreProvider
import whisk.core.entitlement.Collection
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.http.{ErrorResponse, Messages}
import whisk.spi.SpiLoader

/**
 * Tests Activations API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class ActivationsApiTests extends ControllerTestCommon with WhiskActivationsApi {

  /** Activations API tests */
  behavior of "Activations API"

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  def aname() = MakeName.next("activations_tests")

  def checkCount(filter: String, expected: Int, user: Identity = creds) = {
    implicit val tid = transid()
    withClue(s"count did not match for filter: $filter") {
      whisk.utils.retry {
        Get(s"$collectionPath?count=true&$filter") ~> Route.seal(routes(user)) ~> check {
          status should be(OK)
          responseAs[JsObject] shouldBe JsObject(collection.path -> JsNumber(expected))
        }
      }
    }
  }

  //// GET /activations
  it should "get summary activation by namespace" in {
    implicit val tid = transid()
    // create two sets of activation records, and check that only one set is served back
    val creds1 = WhiskAuthHelpers.newAuth()
    (1 to 2).map { i =>
      WhiskActivation(
        EntityPath(creds1.subject.asString),
        aname(),
        creds1.subject,
        ActivationId(),
        start = Instant.now,
        end = Instant.now)
    } foreach { put(entityStore, _) }

    val actionName = aname()
    val activations = (1 to 2).map { i =>
      WhiskActivation(namespace, actionName, creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
    }.toList
    activations foreach { put(activationStore, _) }
    waitOnView(activationStore, namespace.root, 2, WhiskActivation.view)
    whisk.utils.retry {
      Get(s"$collectionPath") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[List[JsObject]]
        activations.length should be(response.length)
        response should contain theSameElementsAs activations.map(_.summaryAsJson)
        response forall { a =>
          a.getFields("for") match {
            case Seq(JsString(n)) => n == actionName.asString
            case _                => false
          }
        }
      }
    }

    // it should "list activations with explicit namespace owned by subject" in {
    whisk.utils.retry {
      Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[List[JsObject]]
        activations.length should be(response.length)
        response should contain theSameElementsAs activations.map(_.summaryAsJson)
        response forall { a =>
          a.getFields("for") match {
            case Seq(JsString(n)) => n == actionName.asString
            case _                => false
          }
        }
      }
    }

    // it should "reject list activations with explicit namespace not owned by subject" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }
  }

  //// GET /activations?docs=true
  it should "return empty list when no activations exist" in {
    implicit val tid = transid()
    whisk.utils.retry { // retry because view will be stale from previous test and result in null doc fields
      Get(s"$collectionPath?docs=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        responseAs[List[JsObject]] shouldBe 'empty
      }
    }
  }

  it should "get full activation by namespace" in {
    implicit val tid = transid()
    // create two sets of activation records, and check that only one set is served back
    val creds1 = WhiskAuthHelpers.newAuth()
    (1 to 2).map { i =>
      WhiskActivation(
        EntityPath(creds1.subject.asString),
        aname(),
        creds1.subject,
        ActivationId(),
        start = Instant.now,
        end = Instant.now)
    } foreach { put(entityStore, _) }

    val actionName = aname()
    val activations = (1 to 2).map { i =>
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId(),
        start = Instant.now,
        end = Instant.now,
        response = ActivationResponse.success(Some(JsNumber(5))))
    }.toList
    activations foreach { put(activationStore, _) }
    waitOnView(activationStore, namespace.root, 2, WhiskActivation.view)

    checkCount("", 2)

    whisk.utils.retry {
      Get(s"$collectionPath?docs=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[List[JsObject]]
        activations.length should be(response.length)
        response should contain theSameElementsAs activations.map(_.toExtendedJson)
      }
    }
  }

  //// GET /activations?docs=true&since=xxx&upto=yyy
  it should "get full activation by namespace within a date range" in {
    implicit val tid = transid()
    // create two sets of activation records, and check that only one set is served back
    val creds1 = WhiskAuthHelpers.newAuth()
    (1 to 2).map { i =>
      WhiskActivation(
        EntityPath(creds1.subject.asString),
        aname(),
        creds1.subject,
        ActivationId(),
        start = Instant.now,
        end = Instant.now)
    } foreach { put(activationStore, _) }

    val actionName = aname()
    val now = Instant.now(Clock.systemUTC())
    val since = now.plusSeconds(10)
    val upto = now.plusSeconds(30)
    implicit val activations = Seq(
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId(),
        start = now.plusSeconds(9),
        end = now.plusSeconds(9)),
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId(),
        start = now.plusSeconds(20),
        end = now.plusSeconds(20)), // should match
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId(),
        start = now.plusSeconds(10),
        end = now.plusSeconds(20)), // should match
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId(),
        start = now.plusSeconds(31),
        end = now.plusSeconds(31)),
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId(),
        start = now.plusSeconds(30),
        end = now.plusSeconds(30))) // should match
    activations foreach { put(activationStore, _) }
    waitOnView(activationStore, namespace.root, activations.length, WhiskActivation.view)

    { // get between two time stamps
      val filter = s"since=${since.toEpochMilli}&upto=${upto.toEpochMilli}"
      val expected = activations.filter { e =>
        (e.start.equals(since) || e.start.equals(upto) || (e.start.isAfter(since) && e.start.isBefore(upto)))
      }

      checkCount(filter, expected.length)

      whisk.utils.retry {
        Get(s"$collectionPath?docs=true&$filter") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[List[JsObject]]
          expected.length should be(response.length)
          response should contain theSameElementsAs expected.map(_.toExtendedJson)
        }
      }
    }

    { // get 'upto' with no defined since value should return all activation 'upto'
      val expected = activations.filter(e => e.start.equals(upto) || e.start.isBefore(upto))
      val filter = s"upto=${upto.toEpochMilli}"

      checkCount(filter, expected.length)

      whisk.utils.retry {
        Get(s"$collectionPath?docs=true&$filter") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[List[JsObject]]
          expected.length should be(response.length)
          response should contain theSameElementsAs expected.map(_.toExtendedJson)
        }
      }
    }

    { // get 'since' with no defined upto value should return all activation 'since'
      whisk.utils.retry {
        val expected = activations.filter(e => e.start.equals(since) || e.start.isAfter(since))
        val filter = s"since=${since.toEpochMilli}"

        checkCount(filter, expected.length)

        Get(s"$collectionPath?docs=true&$filter") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[List[JsObject]]
          expected.length should be(response.length)
          response should contain theSameElementsAs expected.map(_.toExtendedJson)
        }
      }
    }
  }

  //// GET /activations?name=xyz
  it should "accept valid name parameters and reject invalid ones" in {
    implicit val tid = transid()

    Seq(("", OK), ("name=", OK), ("name=abc", OK), ("name=abc/xyz", OK), ("name=abc/xyz/123", BadRequest)).foreach {
      case (p, s) =>
        Get(s"$collectionPath?$p") ~> Route.seal(routes(creds)) ~> check {
          status should be(s)
          if (s == BadRequest) {
            responseAs[String] should include(Messages.badNameFilter(p.drop(5)))
          }
        }
    }
  }

  it should "get summary activation by namespace and action name" in {
    implicit val tid = transid()

    // create two sets of activation records, and check that only one set is served back
    val creds1 = WhiskAuthHelpers.newAuth()
    (1 to 2).map { i =>
      WhiskActivation(
        EntityPath(creds1.subject.asString),
        aname(),
        creds1.subject,
        ActivationId(),
        start = Instant.now,
        end = Instant.now)
    } foreach { put(activationStore, _) }

    val activations = (1 to 2).map { i =>
      WhiskActivation(
        namespace,
        EntityName(s"xyz"),
        creds.subject,
        ActivationId(),
        start = Instant.now,
        end = Instant.now)
    }.toList
    activations foreach { put(activationStore, _) }

    val activationsInPackage = (1 to 2).map { i =>
      WhiskActivation(
        namespace,
        EntityName(s"xyz"),
        creds.subject,
        ActivationId(),
        start = Instant.now,
        end = Instant.now,
        annotations = Parameters("path", s"${namespace.asString}/pkg/xyz"))
    }.toList
    activationsInPackage foreach { put(activationStore, _) }

    waitOnView(activationStore, namespace.addPath(EntityName("xyz")), activations.length, WhiskActivation.filtersView)
    waitOnView(
      activationStore,
      namespace.addPath(EntityName("pkg")).addPath(EntityName("xyz")),
      activationsInPackage.length,
      WhiskActivation.filtersView)

    checkCount("name=xyz", activations.length)

    whisk.utils.retry {
      Get(s"$collectionPath?name=xyz") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[List[JsObject]]
        activations.length should be(response.length)
        response should contain theSameElementsAs activations.map(_.summaryAsJson)
      }
    }

    checkCount("name=pkg/xyz", activations.length)

    whisk.utils.retry {
      Get(s"$collectionPath?name=pkg/xyz") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[List[JsObject]]
        activationsInPackage.length should be(response.length)
        response should contain theSameElementsAs activationsInPackage.map(_.summaryAsJson)
      }
    }
  }

  it should "reject invalid query parameter combinations" in {
    implicit val tid = transid()
    whisk.utils.retry { // retry because view will be stale from previous test and result in null doc fields
      Get(s"$collectionPath?docs=true&count=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(BadRequest)
        responseAs[ErrorResponse].error shouldBe Messages.docsNotAllowedWithCount
      }
    }
  }

  it should "reject activation list when limit is greater than maximum allowed value" in {
    implicit val tid = transid()
    val exceededMaxLimit = Collection.MAX_LIST_LIMIT + 1
    val response = Get(s"$collectionPath?limit=$exceededMaxLimit") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listLimitOutOfRange(Collection.ACTIVATIONS, exceededMaxLimit, Collection.MAX_LIST_LIMIT)
      }
    }
  }

  it should "reject get activation by namespace and action name when action name is not a valid name" in {
    implicit val tid = transid()
    Get(s"$collectionPath?name=0%20") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "reject get activation with invalid since/upto value" in {
    implicit val tid = transid()
    Get(s"$collectionPath?since=xxx") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
    Get(s"$collectionPath?upto=yyy") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  //// GET /activations/id
  it should "get activation by id" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(namespace, aname(), creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
    put(activationStore, activation)

    Get(s"$collectionPath/${activation.activationId.asString}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response should be(activation.toExtendedJson)
    }

    // it should "get activation by name in explicit namespace owned by subject" in
    Get(s"/$namespace/${collection.path}/${activation.activationId.asString}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response should be(activation.toExtendedJson)
    }

    // it should "reject get activation by name in explicit namespace not owned by subject" in
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${activation.activationId.asString}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }
  }

  //// GET /activations/id/result
  it should "get activation result by id" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(namespace, aname(), creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
    put(activationStore, activation)

    Get(s"$collectionPath/${activation.activationId.asString}/result") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response should be(activation.response.toExtendedJson)
    }
  }

  //// GET /activations/id/logs
  it should "get activation logs by id" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(namespace, aname(), creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
    put(activationStore, activation)

    Get(s"$collectionPath/${activation.activationId.asString}/logs") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response should be(activation.logs.toJsonObject)
    }
  }

  //// GET /activations/id/bogus
  it should "reject request to get invalid activation resource" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(namespace, aname(), creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
    put(entityStore, activation)

    Get(s"$collectionPath/${activation.activationId.asString}/bogus") ~> Route.seal(routes(creds)) ~> check {
      status should be(NotFound)
    }
  }

  it should "reject get requests with invalid activation ids" in {
    implicit val tid = transid()
    val activationId = ActivationId().toString
    val tooshort = activationId.substring(0, 31)
    val toolong = activationId + "xxx"
    val malformed = tooshort + "z"

    Get(s"$collectionPath/$tooshort") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] shouldBe Messages.activationIdLengthError(SizeError("Activation id", tooshort.length.B, 32.B))
    }

    Get(s"$collectionPath/$toolong") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] shouldBe Messages.activationIdLengthError(SizeError("Activation id", toolong.length.B, 32.B))
    }

    Get(s"$collectionPath/$malformed") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "reject request with put" in {
    implicit val tid = transid()
    Put(s"$collectionPath/${ActivationId()}") ~> Route.seal(routes(creds)) ~> check {
      status should be(MethodNotAllowed)
    }
  }

  it should "reject request with post" in {
    implicit val tid = transid()
    Post(s"$collectionPath/${ActivationId()}") ~> Route.seal(routes(creds)) ~> check {
      status should be(MethodNotAllowed)
    }
  }

  it should "reject request with delete" in {
    implicit val tid = transid()
    Delete(s"$collectionPath/${ActivationId()}") ~> Route.seal(routes(creds)) ~> check {
      status should be(MethodNotAllowed)
    }
  }

  it should "report proper error when record is corrupted on get" in {
    implicit val materializer = ActorMaterializer()
    val activationStore = SpiLoader
      .get[ArtifactStoreProvider]
      .makeStore[WhiskEntity](whiskConfig, _.dbActivations)(
        WhiskEntityJsonFormat,
        WhiskDocumentReader,
        system,
        logging,
        materializer)
    implicit val tid = transid()
    val entity = BadEntity(namespace, EntityName(ActivationId().toString))
    put(activationStore, entity)

    Get(s"$collectionPath/${entity.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }
}
