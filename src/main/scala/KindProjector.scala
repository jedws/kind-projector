package d_m

import scala.tools.nsc
import nsc.Global
import nsc.Phase
import nsc.plugins.Plugin
import nsc.plugins.PluginComponent
import nsc.transform.Transform
import nsc.transform.InfoTransform
import nsc.transform.TypingTransformers
import nsc.symtab.Flags._
import nsc.ast.TreeDSL
import nsc.typechecker

class KindProjector(val global: Global) extends Plugin {
  val name = "kind-projector"
  val description = "Expand type lambda syntax"
  val components = new KindRewriter(this, global) :: Nil
}

class KindRewriter(plugin: Plugin, val global: Global)
    extends PluginComponent with Transform with TypingTransformers with TreeDSL {

  import global._

  val runsAfter = "parser" :: Nil
  val phaseName = "kind-projector"

  def newTransformer(unit: CompilationUnit) = new MyTransformer(unit)

  class MyTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {

    // reserve some names
    val TypeLambda1 = newTypeName("Lambda")
    val TypeLambda2 = newTypeName("λ")
    val Placeholder = newTypeName("$qmark")
    val CoPlaceholder = newTypeName("$plus$qmark")
    val ContraPlaceholder = newTypeName("$minus$qmark")

    // these will be used for matching but aren't reserved
    val Plus = newTypeName("$plus")
    val Minus = newTypeName("$minus")

    override def transform(tree: Tree): Tree = {

      def rssi(b: String, c: String) = 
        Select(Select(Ident("_root_"), b), newTypeName(c))

      // Handy way to build the bounds that we'll frequently be using.
      def bounds = TypeBoundsTree(rssi("scala", "Nothing"), rssi("scala", "Any"))

      // Handy way to make a TypeName from a Name.
      def makeTypeName(name: Name) =
        newTypeName(name.toString)

      // We use this to create type parameters inside our type project, e.g.
      // the A in: ({type L[A] = (A, Int) => A})#L.
      def makeTypeParam(name: Name) =
        TypeDef(Modifiers(PARAM), makeTypeName(name), Nil, bounds)

      // Like makeTypeParam but with covariance, e.g.
      // ({type L[+A] = ... })#L.
      def makeTypeParamCo(name: Name) =
        TypeDef(Modifiers(PARAM | COVARIANT), makeTypeName(name), Nil, bounds)

      // Like makeTypeParam but with contravariance, e.g.
      // ({type L[-A] = ... })#L.
      def makeTypeParamContra(name: Name) =
        TypeDef(Modifiers(PARAM | CONTRAVARIANT), makeTypeName(name), Nil, bounds)

      // Detects which makeTypeParam* method to call based on name.
      // Names like +A are covariant, names like -A are contravariant,
      // all others are invariant.
      def makeTypeParamFromName(name: Name) =
        if (name.startsWith("$plus")) {
          makeTypeParamCo(newTypeName(name.toString.substring(5)))
        } else if (name.startsWith("$minus")) {
          makeTypeParamContra(newTypeName(name.toString.substring(6)))
        } else {
          makeTypeParam(name)
        }

      // Like makeTypeParam, but can be used recursively in the case of types
      // that are themselves parameterized.
      def makeComplexTypeParam(t: Tree): TypeDef = t match {
        case Ident(name) =>
          makeTypeParamFromName(name)

        case TypeDef(m, nm, ps, bs) =>
          TypeDef(Modifiers(PARAM), nm, ps.map(makeComplexTypeParam), bs)

        case ExistentialTypeTree(AppliedTypeTree(Ident(name), ps), _) =>
          val tparams = ps.map(makeComplexTypeParam)
          TypeDef(Modifiers(PARAM), makeTypeName(name), tparams, bounds)

        case x =>
          unit.error(x.pos, "Can't parse %s (%s)" format (x, x.getClass.getName))
          null.asInstanceOf[TypeDef]
      }

      // Given the list a::as, this method finds the last argument in the list
      // (the "subtree") and returns that separately from the other arguments.
      // The stack is just used to enable tail recursion, and a and as are
      // passed separately to avoid dealing with empty lists.
      def parseLambda(a: Tree, as: List[Tree], stack: List[Tree]): (List[Tree], Tree) =
        as match {
          case Nil => (stack.reverse, a)
          case h :: t => parseLambda(h, t, a :: stack)
        }

      // TODO: If we drop 2.9 support, we should use TermName("_")
      // instead of "_".

      // Builds the horrendous type projection tree. To remind the reader,
      // given List("A", "B") and <(A, Int, B)> we are generating a tree for
      // ({type L[A, B] = (A, Int, B)})#L.
      def makeTypeProjection(innerTypes: List[TypeDef], subtree: Tree) =
        SelectFromTypeTree(
          CompoundTypeTree(
            Template(
              rssi("scala", "AnyRef") :: Nil,
              ValDef(Modifiers(0), "_", TypeTree(), EmptyTree),
              TypeDef(
                Modifiers(0),
                newTypeName("L_kp"),
                innerTypes,
                super.transform(subtree)) :: Nil)),
          newTypeName("L_kp"))

      // This method handles the explicit type lambda case, e.g.
      // Lambda[(A, B) => Function2[A, Int, B]] case.
      def handleLambda(a: Tree, as: List[Tree]) = {
        val (args, subtree) = parseLambda(a, as, Nil)
        val innerTypes = args.map {
          case Ident(name) =>
            makeTypeParamFromName(name)

          case AppliedTypeTree(Ident(Plus), Ident(name) :: Nil) =>
            makeTypeParamCo(name)

          case AppliedTypeTree(Ident(Minus), Ident(name) :: Nil) =>
            makeTypeParamContra(name)

          case AppliedTypeTree(Ident(name), ps) =>
            val tparams = ps.map(makeComplexTypeParam)
            TypeDef(Modifiers(PARAM), makeTypeName(name), tparams, bounds)

          case ExistentialTypeTree(AppliedTypeTree(Ident(name), ps), _) =>
            val tparams = ps.map(makeComplexTypeParam)
            TypeDef(Modifiers(PARAM), makeTypeName(name), tparams, bounds)

          case x =>
            unit.error(x.pos, "Can't parse %s (%s)" format (x, x.getClass.getName))
            null.asInstanceOf[TypeDef]
        }
        makeTypeProjection(innerTypes, subtree)
      }

      // This method handles the implicit type lambda case, e.g.
      // Function2[?, Int, ?].
      def handlePlaceholders(t: Tree, as: List[Tree]) = {
        // create a new type argument list, catching placeholders and create
        // individual identifiers for them.
        val xyz = as.zipWithIndex.map {
          case (Ident(Placeholder), i) =>
            (Ident(newTypeName("X_kp%d" format i)), Some(Right(Placeholder)))
          case (Ident(CoPlaceholder), i) =>
            (Ident(newTypeName("X_kp%d" format i)), Some(Right(CoPlaceholder)))
          case (Ident(ContraPlaceholder), i) =>
            (Ident(newTypeName("X_kp%d" format i)), Some(Right(ContraPlaceholder)))
          case (ExistentialTypeTree(AppliedTypeTree(Ident(Placeholder), ps), _), i) =>
            (Ident(newTypeName("X_kp%d" format i)), Some(Left(ps.map(makeComplexTypeParam))))
          case (a, i) =>
            (super.transform(a), None)
        }

        // for each placeholder, create a type parameter
        val innerTypes = xyz.collect {
          case (Ident(name), Some(Right(Placeholder))) =>
            makeTypeParam(name)
          case (Ident(name), Some(Right(CoPlaceholder))) =>
            makeTypeParamCo(name)
          case (Ident(name), Some(Right(ContraPlaceholder))) =>
            makeTypeParamContra(name)
          case (Ident(name), Some(Left(tparams))) =>
            TypeDef(Modifiers(PARAM), makeTypeName(name), tparams, bounds)
        }

        val args = xyz.map(_._1)

        // if we didn't have any placeholders use the normal transformation.
        // otherwise build a type projection.
        if (innerTypes.isEmpty) super.transform(tree)
        else makeTypeProjection(innerTypes, AppliedTypeTree(t, args))
      }

      tree match {
        case Template(parents, self, body) =>
          Template(parents.map(transform), self, body.map(transform))

        // Lambda[A => Either[A, Int]] case.
        case AppliedTypeTree(Ident(TypeLambda1), AppliedTypeTree(_, a :: as) :: Nil) =>
          handleLambda(a, as)

        // λ[A => Either[A, Int]] case.
        case AppliedTypeTree(Ident(TypeLambda2), AppliedTypeTree(_, a :: as) :: Nil) =>
          handleLambda(a, as)

        // Either[?, Int] case (if no ? present this is a noop)
        case AppliedTypeTree(t, as) =>
          handlePlaceholders(t, as)

        // Otherwise, carry on as normal.
        case _ =>
          super.transform(tree)
      }
    }
  }
}
