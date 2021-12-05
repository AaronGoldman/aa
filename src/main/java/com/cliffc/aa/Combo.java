package com.cliffc.aa;

import com.cliffc.aa.node.FunNode;
import com.cliffc.aa.node.Node;
import com.cliffc.aa.node.WorkNode;
import com.cliffc.aa.util.VBitSet;

import static com.cliffc.aa.AA.unimpl;

/** Combined Global Constant Propagation and Hindly-Milner with extensions.

See HM/HM.java for a complete stand-alone research version.

==============================================================================

Treats Hindly-Milner as a Monotone Analysis Framework; converted to a worklist
style.  The type variables are monotonically unified, gradually growing over
time - and this is treated as the MAF lattice.  Some normal Algo-W work has
already been done; e.g. discovering identifier sources (SSA form), and building
the non-generative set.  Because of the non-local unification behavior type
variables include a "dependent" set; a set of Nodes put back on the worklist if
this type unifies, beyond the expected Node graph neighbors.

The normal HM unification steps are treated as the MAF transfer functions,
taking type variables as inputs and producing new, unified, type variables.
Because unification happens in-place (normal disjoint-set union), the transfer
functions are executed for side effects only and return a progress flag.  The
transfer functions are virtual calls on Nodes, similar to the existing
Node.value() calls.

HM Bases include anything from the GCP lattice, but 'widened' to form borders
between e.g. ints and pointers.  Includes polymorphic structures and fields
(structural typing not duck typing), polymorphic nil-checking and an error type
variable.  Both HM and GCP types fully support recursive types.

Unification typically makes many temporary type variables and immediately
unifies them.  For efficiency, this algorithm checks to see if unification
requires an allocation first, instead of just "allocate and unify".  The major
place this happens is identifiers, which normally make a "fresh" copy of their
type-var, then unify.  I use a combined "make-fresh-and-unify" unification
algorithm there.  It is a structural clone of the normal unify, except that it
lazily makes a fresh-copy of the left-hand-side on demand only; typically
discovering that no fresh-copy is required.

To engineer and debug the algorithm, the unification step includes a flag to
mean "actually unify and report a progress" vs "report if progress".  The
report-only mode is aggressively asserted for; all Nodes that can make progress
are asserted as on the worklist.

==============================================================================

Global Optimistic Constant Propagation is treated as the usual Monotone
Analysis Framework.  Passed in the parsed program state (including any return
result, i/o and memory state).  Returns the most-precise types possible, and
replaces computed constants with constant nodes.  GCP types are computed using
the virtual value() call, which computes a new type from the node neighbors
without reference to the old type.  It has no side-effects.

Besides the obvious GCP algorithm (and the type-precision that results from the
analysis), GCP does a few more things:

GCP builds an explicit Call-Graph.  Before GCP not all callers are known and
this is approximated by being called by a ScopeNode as a permanently available
unknown caller.  If the whole program is available to us then we can compute
all callers conservatively and fairly precisely - we may have extra never-taken
caller/callee edges, but no missing caller/callee edges.  These edges are
virtual before GCP.  During GCP we discover most paths are dead, and we add in
physical CG edges as possible calls are discovered.

GCP discovers functions which escape at the top-most level, and wires the
RetNode to the top-most Scope to mimic future unknown callers.

GCP resolves all ambiguous (overloaded) calls, using the precise types first,
and then inserting conversions using a greedy decision.  If this is not
sufficient to resolve all calls, the program is ambiguous and wrong.

==============================================================================

Global Optimistic Liveness is also computed.  This is the reverse version of
GCP, treated as the usual Monotone Analysis Framework.  Liveness is precise
through memory, tracking in-use aliases and fields of structures.  Similar to
GCP, liveness is computed using a virtual live() call - which typically uses
the default version.  Each live-use edge is checked with a virtual live_use
call, which is typically overloaded for every Node.

==============================================================================

The combined algorithm includes transfer functions taking facts from one MAF
lattice and produces results in some other MAF lattice.

For the GCP->HM direction, the HM IfNode has a custom transfer function instead
of the usual one.  Unification looks at the GCP value, and unifies either the
true arm, or the false arm, or both or neither.  In this way GCP allows HM to
avoid picking up constraints from dead code.

Also for GCP->HM, the HM ground terms or base terms include anything from the
GCP lattice, instead of just the usual int/float/datum/string set.

For the HM->GCP direction, the GCP CallEpi has a customer transfer function
where the result from a call gets lifted (JOINed) based on the matching GCP
inputs - and the match comes from using the same HM type-var on both inputs and
outputs.  This allows e.g. "map" calls which typically merge many GCP values at
many call sites and thus end up typed as a Scalar to Scalar, to improve the GCP
type on a per-call-site basis.

Also for the HM->GCP direction, HM is used to lift external calls to escaped
functions.  This probably needs more explaination: Functions which do not
escape the borders of the compilation unit are treated as private, and all
their callers are known (the Call Graph is found by GCP as explained above).
Functions which DO escape are treated "as-if" called by an unknown caller with
the most conservative possible arguments; e.g. exposed module entry points, or
functions defined in the REPL and yet to be called by a future REPL entry.
Without HM, GCP assumes an external caller calls with Scalar arguments (and
memory filled with Scalars); with HM the GCP inputs are lifted to the HM
structural types.

Also for the HM->GCP direction, loads from external pointers are lifted.  The
HM-required memory structure is not reflected in the external memory, nor does
GCP have the external alias numbers.

For the Live->HM direction, dead Nodes do not unify hence do not force structure
on the HM variables.

For the GCP->Live direction, Nodes which (may) compute a constant do not need
their inputs, and so their inputs are treated as dead.

==============================================================================

10/5/2021 - As of this writing, the full GCP+HM+Live combined algorithm "gets
stuck" too high when typing this expression: "f={ x -> x ? (f(x.n),x.v&x.v)}"
"f" escapes at the top level, and so is first called by an external caller and
only after that recursively calls itself.


 */
public abstract class Combo {
  static public boolean HM_FREEZE;

  public static void opto() {
    Env.GVN._opt_mode = GVNGCM.Mode.Opto;

    // General worklist algorithm
    WorkNode work = new WorkNode("Combo",false) { @Override public Node apply(Node n) { throw unimpl(); } };

    // Set all values to ANY and lives to DEAD, their most optimistic types.
    // Set all type-vars to Leafs.
    Env.START.walk_initype(work);
    // The mode-flip to Opto allows some Nodes to lift.
    // If not doing GCP, lift them now (pessimistically)
    if( !AA.DO_GCP ) Env.GVN.iter(GVNGCM.Mode.Opto);

    // Make the non-gen set in a pre-pass
    for( FunNode fun : FunNode.FUNS ) if( fun!=null ) fun._nongen=null; // Clear old stuff
    for( FunNode fun : FunNode.FUNS ) if( fun!=null && !fun.is_dead() ) fun.prep_nongen();// Make new
    assert Env.START.more_work(work,false)==0; // Initial conditions are correct

    // Init
    HM_FREEZE = false;
    int work_cnt=0;

    // Pass 1: Everything starts high/top/leaf and falls; escaping function args are assumed high
    work_cnt += main_work_loop(work);
    assert Env.START.more_work(work,false)==0;

    // Pass 2: Give up on the Root GCP arg types.  Drop them to the best Root
    // approximation and never lift again.
    Node.RESET_VISIT.clear();
    Env.START.walk_combo_phase2(work,Env.FILE._scope.top_escapes());
    work_cnt += main_work_loop(work);
    assert Env.START.more_work(work,false)==0;

    // Pass 3: H-M types freeze, escaping function args are assumed lowest H-M compatible and
    // GCP types continue to run downhill.
    HM_FREEZE = true;
    ////prog.visit((syn) -> { syn.add_val_work(null,work); return work.add(syn); }, (a,b)->null);
    work_cnt += main_work_loop(work);
    assert Env.START.more_work(work,false)==0;

    Node.VALS.clear();
    Env.START.walk_opt(new VBitSet());
  }

  static int main_work_loop( WorkNode work ) {

    int cnt=0;                  // Debug counter

    // Analysis phase.
    // Work down list until all reachable nodes types quit falling
    Node n;
    while( (n=work.pop()) != null ) {
      cnt++; assert cnt < 100000; // Infinite loop check

      if( AA.DO_GCP ) {
        // Forwards flow
        n.combo_forwards(work);

        // Backwards flow
        n.combo_backwards(work);

      }

      // H-M unification
      if( AA.DO_HMT )
        n.combo_unify(work);

      // Very expensive assert: everything that can make progress is on worklist
      //assert Env.START.more_work(work,false)==0;
    }
    return cnt;
  }

  static void reset() { HM_FREEZE=false; }
}
