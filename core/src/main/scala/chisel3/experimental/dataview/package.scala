// SPDX-License-Identifier: Apache-2.0

package chisel3.experimental

import chisel3._
import chisel3.internal.{AggregateViewBinding, TopBinding, ViewBinding, requireIsChiselType}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.immutable.LazyList // Needed for 2.12 alias

package object dataview {
  case class InvalidViewException(message: String) extends ChiselException(message)

  private def nonTotalViewException(dataView: DataView[_, _], target: Any, view: Data, targetFields: Seq[String], viewFields: Seq[String]) = {
    def missingMsg(name: String, fields: Seq[String]): Option[String] = {
      val str = fields.mkString(", ")
      fields.size match {
        case 0 => None
        case 1 => Some(s"$name field '$str' is missing")
        case _ => Some(s"$name fields '$str' are missing")
      }
    }
    val vs = missingMsg("view", viewFields)
    val ts = missingMsg("target", targetFields)
    val reasons = (ts ++ vs).mkString(" and ").capitalize
    val suggestion = if (ts.nonEmpty) "\n  If the view *should* be non-total, try a 'PartialDataView'." else ""
    val msg = s"Viewing $target as $view is non-Total!\n  $reasons.\n  DataView used is $dataView.$suggestion"
    throw InvalidViewException(msg)
  }

  // Safe for unbound
  private def isView(d: Data): Boolean = d.topBindingOpt.exists {
    case (_: ViewBinding | _: AggregateViewBinding) => true
    case _ => false
  }

  // TODO should this be moved to class Aggregate / can it be unified with Aggregate.bind?
  private def doBind[T : DataProduct, V <: Data](target: T, view: V, dataView: DataView[T, V]): Unit = {
    val mapping = dataView.mapping(target, view)
    val total = dataView.total
    // Lookups to check the mapping results
    val viewFieldLookup: Map[Data, String] = getRecursiveFields(view, "_").toMap
    val targetContains: Data => Boolean = implicitly[DataProduct[T]].dataSet(target)

    // Resulting bindings for each Element of the View
    val childBindings =
      new mutable.HashMap[Data, mutable.ListBuffer[Element]] ++
        viewFieldLookup.view
          .collect { case (elt: Element, _) => elt }
          .map(_ -> new mutable.ListBuffer[Element])

    def viewFieldName(d: Data): String =
      viewFieldLookup.get(d).map(_ + " ").getOrElse("") + d.toString

    // Helper for recording the binding of each
    def onElt(te: Element, ve: Element): Unit = {
      // TODO can/should we aggregate these errors?
      def err(name: String, arg: Data) =
        throw new Exception(s"View mapping must only contain Elements within the $name, got $arg")

      // The elements may themselves be views, look through the potential chain of views for the Elements
      // that are actually members of the target or view
      val tex = unfoldView(te).find(targetContains).getOrElse(err("Target", te))
      val vex = unfoldView(ve).find(viewFieldLookup.contains).getOrElse(err("View", ve))

      // TODO need to check widths but a less strict version than typeEquivalent
      if (tex.getClass != vex.getClass) {
        val fieldName = viewFieldName(vex)
        throw new Exception(s"Field $fieldName specified as view of non-type-equivalent value $tex")
      }
      childBindings(vex) += tex
    }

    mapping.foreach {
      // Special cased because getMatchedFields checks typeEquivalence on Elements (and is used in Aggregate path)
      // Also saves object allocations on common case of Elements
      case (ae: Element, be: Element) => onElt(ae, be)

      case (aa: Aggregate, ba: Aggregate) =>
        if (!ba.typeEquivalent(aa)) {
          val fieldName = viewFieldLookup(ba)
          throw new Exception(s"field $fieldName specified as view of non-type-equivalent value $aa")
        }
        getMatchedFields(aa, ba).foreach {
          case (aelt: Element, belt: Element) => onElt(aelt, belt)
          case _ => // Ignore matching of Aggregates
        }
    }

    // Errors in totality of the View, use var List to keep fast path cheap (no allocation)
    var viewNonTotalErrors: List[Data] = Nil
    var targetNonTotalErrors: List[String] = Nil

    val targetSeen: Option[mutable.Set[Data]] = if (total) Some(mutable.Set.empty[Data]) else None

    val resultBindings = childBindings.map { case (data, targets) =>
      val targetsx = targets match {
        case collection.Seq(target: Element) => target
        case collection.Seq() =>
          viewNonTotalErrors = data :: viewNonTotalErrors
          data.asInstanceOf[Element] // Return the Data itself, will error after this map, cast is safe
        case x =>
          throw new Exception(s"Got $x, expected Seq(_: Direct)")
      }
      // TODO record and report aliasing errors
      targetSeen.foreach(_ += targetsx)
      data -> targetsx
    }.toMap

    // Check for totality of Target
    targetSeen.foreach { seen =>
      val lookup = implicitly[DataProduct[T]].dataIterator(target, "_")
      for (missed <- lookup.collect { case (d: Element, name) if !seen(d) => name }) {
        targetNonTotalErrors = missed :: targetNonTotalErrors
      }
    }
    if (viewNonTotalErrors != Nil || targetNonTotalErrors != Nil) {
      val viewErrors = viewNonTotalErrors.map(f => viewFieldLookup.getOrElse(f, f.toString))
      nonTotalViewException(dataView, target, view, targetNonTotalErrors, viewErrors)
    }

    view.bind(AggregateViewBinding(resultBindings))
  }

  // TODO is this right place to put this?
  /** Provides `viewAs` for types that are supported as [[DataView]] targets */
  implicit class DataViewable[T : DataProduct](target: T) {
    def viewAs[V <: Data](view: V)(implicit dataView: DataView[T, V]): V = {
      requireIsChiselType(view, "viewAs")
      val result: V = view.cloneTypeFull

      doBind(target, result, dataView)
      result
    }
  }

  // Traces an Element that may (or may not) be a view until it no longer maps
  // Inclusive of the argument
  private def unfoldView(elt: Element): LazyList[Element] = {
    def rec(e: Element): LazyList[Element] = e.topBindingOpt match {
      case Some(ViewBinding(target)) => target #:: rec(target)
      case Some(AggregateViewBinding(mapping)) =>
        val target = mapping(e)
        target #:: rec(target)
      case Some(_) | None => LazyList.empty
    }
    elt #:: rec(elt)
  }

  /** Turn any [[Element]] that could be a View into a concrete Element
    *
    * This is the fundamental "unwrapping" or "tracing" primitive operation for handling Views within
    * Chisel.
    */
  private[chisel3] def reify(elt: Element): Element =
    reify(elt, elt.topBinding)

  /** Turn any [[Element]] that could be a View into a concrete Element
    *
    * This is the fundamental "unwrapping" or "tracing" primitive operation for handling Views within
    * Chisel.
    */
  @tailrec private[chisel3] def reify(elt: Element, topBinding: TopBinding): Element =
    topBinding match {
      case ViewBinding(target) => reify(target, elt.topBinding)
      case _ => elt
    }
}
