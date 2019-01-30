package com.wavesplatform.matcher

import java.time.{Instant, Duration => JDuration}

import akka.actor.{Actor, Cancellable}
import akka.pattern.pipe
import com.wavesplatform.account.Address
import com.wavesplatform.matcher.Matcher.StoreEvent
import com.wavesplatform.matcher.OrderDB.orderInfoOrdering
import com.wavesplatform.matcher.model.Events.{OrderAdded, OrderCanceled, OrderExecuted}
import com.wavesplatform.matcher.model.LimitOrder.OrderStatus
import com.wavesplatform.matcher.model.{LimitOrder, OrderInfo, OrderValidator}
import com.wavesplatform.matcher.queue.QueueEvent
import com.wavesplatform.state.{ByteStr, Portfolio}
import com.wavesplatform.transaction.AssetId
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order}
import com.wavesplatform.utils.{LoggerFacade, ScorexLogging, Time}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

class AddressActor(
    owner: Address,
    portfolio: => Portfolio,
    maxTimestampDrift: FiniteDuration,
    cancelTimeout: FiniteDuration,
    time: Time,
    orderDB: OrderDB,
    storeEvent: StoreEvent,
) extends Actor
    with ScorexLogging {

  import AddressActor._
  import context.dispatcher

  protected override def log = LoggerFacade(LoggerFactory.getLogger(s"AddressActor[$owner]"))

  private val pendingCancellation = mutable.AnyRefMap.empty[ByteStr, Promise[Resp]]
  private val pendingPlacement    = mutable.AnyRefMap.empty[ByteStr, Promise[Resp]]

  private val activeOrders  = mutable.AnyRefMap.empty[ByteStr, LimitOrder]
  private val openVolume    = mutable.AnyRefMap.empty[Option[AssetId], Long].withDefaultValue(0L)
  private val expiration    = mutable.AnyRefMap.empty[ByteStr, Cancellable]
  private var latestOrderTs = 0L

  private def reserve(limitOrder: LimitOrder): Unit =
    for ((id, b) <- limitOrder.requiredBalance if b != 0) {
      val prevBalance = openVolume(id)
      val newBalance  = prevBalance + b
      log.trace(s"${limitOrder.order.id()}: $id -> +$b ($prevBalance -> $newBalance)")
      openVolume += id -> newBalance
    }

  private def release(orderId: ByteStr): Unit =
    for (limitOrder <- activeOrders.get(orderId); (id, b) <- limitOrder.requiredBalance if b != 0) {
      val prevBalance = openVolume(id)
      val newBalance  = prevBalance - b
      log.trace(s"${limitOrder.order.id()}: $id -> -$b ($prevBalance -> $newBalance)")
      openVolume += id -> newBalance
    }

  private def updateTimestamp(newTimestamp: Long): Unit = if (newTimestamp > latestOrderTs) {
    latestOrderTs = newTimestamp
  }

  private def tradableBalance(assetId: Option[AssetId]): Long = {
    val p = portfolio
    assetId.fold(p.spendableBalance)(p.assets.getOrElse(_, 0L)) - openVolume(assetId)
  }

  private val validator =
    OrderValidator.accountStateAware(owner,
                                     tradableBalance,
                                     activeOrders.size,
                                     latestOrderTs - maxTimestampDrift.toMillis,
                                     id => activeOrders.contains(id) || orderDB.contains(id)) _

  private def handleCommands: Receive = {
    case PlaceOrder(o) =>
      log.debug(s"New order: ${o.json()}")
      validator(o) match {
        case Right(_) =>
          updateTimestamp(o.timestamp)
          orderDB.saveOrder(o)
          val lo = LimitOrder(o)
          activeOrders += o.id() -> lo
          reserve(lo)
          latestOrderTs = latestOrderTs.max(lo.order.timestamp)
          storePlaced(o).pipeTo(sender())
        case Left(error) =>
          sender() ! api.OrderRejected(error)
      }
    case CancelOrder(id) =>
      activeOrders.get(id) match {
        case Some(lo) => storeCanceled(lo.order.assetPair, lo.order.id()) pipeTo sender
        case None     => sender() ! api.OrderCancelRejected(s"Order $id not found")
      }
    case CancelAllOrders(maybePair, timestamp) =>
      if ((timestamp - latestOrderTs).abs <= maxTimestampDrift.toMillis) {
        val batchCancelFutures = for {
          lo <- activeOrders.values
          if maybePair.forall(_ == lo.order.assetPair)
        } yield storeCanceled(lo.order.assetPair, lo.order.id()).map(lo.order.id() -> _)

        Future.sequence(batchCancelFutures).map(_.toMap).map(api.BatchCancelCompleted).pipeTo(sender())
      } else {
        sender() ! api.OrderCancelRejected("Invalid timestamp")
      }
    case CancelExpiredOrder(id) =>
      expiration.remove(id)
      for (lo <- activeOrders.get(id)) {
        if ((lo.order.expiration - time.correctedTime()).max(0L).millis <= ExpirationThreshold) {
          log.trace(s"Order $id expired, storing cancel event")
          storeEvent(QueueEvent.Canceled(lo.order.assetPair, id)).andThen {
            case Success(_) => log.trace(s"Successfully stored cancel event for expired order $id")
            case Failure(e) => log.warn(s"Error cancelling expired order $id", e)
          }
        } else {
          scheduleExpiration(lo.order)
        }
      }
  }

  private def store(id: ByteStr, event: QueueEvent, eventCache: mutable.Map[ByteStr, Promise[Resp]]): Future[Resp] = {
    storeEvent(event).transformWith {
      case Failure(e) =>
        log.error(s"Error persisting $event", e)
        Future.successful(api.OrderCancelRejected("Error persisting request"))
      case Success(_) =>
        val promisedResponse = Promise[api.WrappedMatcherResponse]
        eventCache += id -> promisedResponse
        promisedResponse.future
    }
  }

  private def storeCanceled(assetPair: AssetPair, id: ByteStr): Future[Resp] = store(id, QueueEvent.Canceled(assetPair, id), pendingCancellation)

  private def storePlaced(order: Order): Future[Resp] = store(order.id(), QueueEvent.Placed(order), pendingPlacement)

  private def handleStatusRequests: Receive = {
    case GetOrderStatus(orderId) =>
      sender() ! activeOrders.get(orderId).fold(orderDB.status(orderId))(activeStatus)
    case GetOrders(maybePair, onlyActive) =>
      log.trace(s"Loading ${if (onlyActive) "active" else "all"} ${maybePair.fold("")(_.toString + " ")}orders")
      val matchingActiveOrders = (for {
        lo <- activeOrders.values
        if maybePair.forall(_ == lo.order.assetPair)
      } yield
        lo.order
          .id() -> OrderInfo(lo.order.orderType, lo.order.amount, lo.order.price, lo.order.timestamp, activeStatus(lo), lo.order.assetPair)).toSeq.sorted

      log.trace(s"Collected ${matchingActiveOrders.length} active orders")

      sender() ! (if (onlyActive) matchingActiveOrders else orderDB.loadRemainingOrders(owner, maybePair, matchingActiveOrders))
    case GetTradableBalance(pair) =>
      sender() ! Set(pair.amountAsset, pair.priceAsset).map(id => id -> tradableBalance(id)).toMap
    case GetReservedBalance =>
      sender() ! openVolume.filter(_._2 > 0).toMap
  }

  private def handleExecutionEvents: Receive = {
    case OrderAdded(submitted) if submitted.order.sender.toAddress == owner =>
      log.trace(s"OrderAdded(${submitted.order.id()})")
      updateTimestamp(submitted.order.timestamp)
      release(submitted.order.id())
      handleOrderAdded(submitted)
    case e @ OrderExecuted(submitted, counter, _) =>
      log.trace(s"OrderExecuted(${submitted.order.id()}, ${counter.order.id()}), amount = ${e.executedAmount}")
      handleOrderExecuted(e.submittedRemaining)
      handleOrderExecuted(e.counterRemaining)

    case OrderCanceled(lo, unmatchable) =>
      pendingCancellation.remove(lo.order.id()).foreach(_.success(api.OrderCanceled(lo.order.id())))
      if (activeOrders.contains(lo.order.id())) {
        log.trace(s"OrderCanceled(${lo.order.id()}, system=$unmatchable)")
        release(lo.order.id())
        val filledAmount = lo.order.amount - lo.amount
        handleOrderTerminated(lo, if (unmatchable) LimitOrder.Filled(filledAmount) else LimitOrder.Cancelled(filledAmount))
      }
  }

  private def scheduleExpiration(order: Order): Unit = {
    val timeToExpiration = (order.expiration - time.correctedTime()).max(0L)
    log.trace(s"Order ${order.id()} will expire in ${JDuration.ofMillis(timeToExpiration)}, at ${Instant.ofEpochMilli(order.expiration)}")
    expiration +=
      order.id() -> context.system.scheduler.scheduleOnce(timeToExpiration.millis, self, CancelExpiredOrder(order.id()))
  }

  private def handleOrderAdded(lo: LimitOrder): Unit = {
    reserve(lo)
    activeOrders += lo.order.id() -> lo
    pendingPlacement.remove(lo.order.id()).foreach(_.success(api.OrderAccepted(lo.order)))
    scheduleExpiration(lo.order)
  }

  private def handleOrderExecuted(remaining: LimitOrder): Unit = if (remaining.order.sender.toAddress == owner) {
    updateTimestamp(remaining.order.timestamp)
    release(remaining.order.id())
    if (remaining.isValid) {
      handleOrderAdded(remaining)
    } else {
      pendingPlacement.remove(remaining.order.id()).foreach(_.success(api.OrderAccepted(remaining.order)))
      val actualFilledAmount = remaining.order.amount - remaining.amount
      handleOrderTerminated(remaining, LimitOrder.Filled(actualFilledAmount))
    }
  }

  private def handleOrderTerminated(lo: LimitOrder, status: OrderStatus): Unit = {
    log.trace(s"Order ${lo.order.id()} terminated: $status")
    pendingCancellation.remove(lo.order.id()).foreach(_.success(api.OrderCancelRejected("Order already terminated")))
    expiration.remove(lo.order.id()).foreach(_.cancel())
    activeOrders.remove(lo.order.id())
    orderDB.saveOrderInfo(
      lo.order.id(),
      owner,
      OrderInfo(lo.order.orderType, lo.order.amount, lo.order.price, lo.order.timestamp, status, lo.order.assetPair)
    )
  }

  def receive: Receive = handleCommands orElse handleExecutionEvents orElse handleStatusRequests
}

object AddressActor {
  private val ExpirationThreshold = 50.millis

  private type Resp = api.WrappedMatcherResponse

  private def activeStatus(lo: LimitOrder): OrderStatus =
    if (lo.amount == lo.order.amount) LimitOrder.Accepted else LimitOrder.PartiallyFilled(lo.order.amount - lo.amount)

  sealed trait Command

  case class GetOrderStatus(orderId: ByteStr)                             extends Command
  case class GetOrders(assetPair: Option[AssetPair], onlyActive: Boolean) extends Command
  case class GetTradableBalance(assetPair: AssetPair)                     extends Command
  case object GetReservedBalance                                          extends Command
  case class PlaceOrder(order: Order)                                     extends Command
  case class CancelOrder(orderId: ByteStr)                                extends Command
  case class CancelAllOrders(pair: Option[AssetPair], timestamp: Long)    extends Command

  private case class CancelExpiredOrder(orderId: ByteStr)
}
