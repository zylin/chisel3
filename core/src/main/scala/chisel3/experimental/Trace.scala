package chisel3.experimental

import chisel3.internal.HasId
import chisel3.{Aggregate, Data, Element, Module}
import firrtl.AnnotationSeq
import firrtl.annotations.{Annotation, CompleteTarget, SingleTargetAnnotation}
import firrtl.transforms.DontTouchAllTargets

/** The util that records the reference map from original [[Data]]/[[Module]] annotated in Chisel and final FIRRTL.
  * @example
  * {{{
  *   class Dut extends Module {
  *     val a = WireDefault(Bool())
  *     Trace.traceName(a)
  *   }
  *   val annos = (new ChiselStage).execute(Seq(ChiselGeneratorAnnotation(() => new Dut)))
  *   val dut = annos.collectFirst { case DesignAnnotation(dut) => dut }.get.asInstanceOf[CollideModule]
  *   // get final reference of `a` Seq(ReferenceTarget("Dut", "Dut", Seq.empty, "a", Seq.empty))
  *   val firrtlReferenceOfDutA = finalTarget(annos)(dut.a)
  * }}}
  * */
object Trace {

  /** Trace a Instance name. */
  def traceName(x: Module): Unit = {
    annotate(new ChiselAnnotation {
      def toFirrtl: Annotation = TraceNameAnnotation(x.toAbsoluteTarget, x.toAbsoluteTarget)
    })
  }

  /** Trace a Data name. */
  def traceName(x: Data): Unit = {
    x match {
      case aggregate: Aggregate =>
        annotate(new ChiselAnnotation {
          def toFirrtl: Annotation = TraceNameAnnotation(aggregate.toAbsoluteTarget, aggregate.toAbsoluteTarget)
        })
        aggregate.getElements.foreach(traceName)
      case element: Element =>
        annotate(new ChiselAnnotation {
          def toFirrtl: Annotation = TraceNameAnnotation(element.toAbsoluteTarget, element.toAbsoluteTarget)
        })
    }
  }

  /** An Annotation that records the original target annotate from Chisel.
    *
    * @param target target that should be renamed by [[firrtl.RenameMap]] in the firrtl transforms.
    * @param chiselTarget original annotated target in Chisel, which should not be changed or renamed in FIRRTL.
    */
  private case class TraceNameAnnotation[T <: CompleteTarget](target: T, chiselTarget: T)
    extends SingleTargetAnnotation[T]
    with DontTouchAllTargets {
    def duplicate(n: T): Annotation = this.copy(target = n)
  }

  /** Get [[CompleteTarget]] of the target `x` for `annos`.
    * This API can be used to find the final reference to a signal or module which is marked by `traceName`
    */
  def finalTarget(annos: AnnotationSeq)(x: HasId): Seq[CompleteTarget] = finalTargetMap(annos)
    .getOrElse(x.toAbsoluteTarget, Seq.empty)

  /** Get all traced signal/module for `annos`
    * This API can be used to gather all final reference to the signal or module which is marked by `traceName`
    */
  def finalTargetMap(annos: AnnotationSeq): Map[CompleteTarget, Seq[CompleteTarget]] = annos.collect {
      case TraceNameAnnotation(t, chiselTarget) => chiselTarget -> t
    }.groupBy(_._1).map{case (k, v) => k -> v.map(_._2)}
}
