package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.*;

import org.jetbrains.annotations.NotNull;

import java.util.BitSet;

// Call/apply node.
//
// Control is not required for an apply but inlining the function body will
// require it; slot 0 is for Control.  Slot 1 is a function value - a Node
// typed as a TypeFunPtr; can be a FunPtrNode, an Unresolved, or e.g. a Phi or
// a Load.  Slot 2 is for memory.  Slots 3+ are for other args.
//
// When the function type simplifies to a single TypeFunPtr, the Call can inline.
//
// The Call-graph is lazily discovered during GCP/opto.  Before then all
// functions are kept alive with a special control (see FunNode), and all Calls
// are assumed to call all functions... unless their fun() input is a trival
// function constant.
//
// As the Call-graph is discovered, Calls are "wired" to make it explicit: the
// Call control is wired to the FunNode/Region; call args are wired directly to
// function ParmNodes/PhiNode; the CallEpi is wired to the function RetNode.
// After GCP/opto the call-graph is explicit and fairly precise.  The call-site
// index is just like a ReturnPC value on a real machine; it dictates which of
// several possible returns apply... and can be merged like a PhiNode.
//
// Memory into   a Call is limited to call-reachable read-write memory.
// Memory out of a Call is limited to call-reachable writable   memory.
//
// ASCIIGram: Vertical   lines are part of the original graph.
//            Horizontal lines are lazily wired during GCP.
//
// TFP&Math
//  Memory: limited to reachable
//  |  |  arg0 arg1
//  |  \  |   /           Other Calls
//  |   | |  /             /  |  \
//  |   v v v             /   |   \
//  |    Call            /    |    \
//  |    C/M/Args       /     |     \
//  |      +--------->------>------->            Wired during GCP
//  |               FUN0   FUN1   FUN2
//  |               +--+   +--+   +--+
//  |               |  |   |  |   |  |
//  |               |  |   |  |   |  |
//  |               +--+   +--+   +--+
//  |          /-----Ret<---Ret<---Ret--\        Wired during GCP
//  |   CallEpi     fptr   fptr   fptr  Other
//  |    CProj         \    |    /       CallEpis
//  |    MProj          \   |   /
//  |    DProj           TFP&Math
//  \   / | \
//  MemMerge: limited to reachable writable
//
// Poster Child For Escape Analysis:   treemap = {t f -> t ? @{l=map(t.l,f);r=map(t.r,f);v=f(t.v)} : 0}
// treemap = {tree fun ->
//   new  = new struct{left,right,val}
//   mem0 = merge(fun.mem,new)
//   tmp1 = ld tree.left;
//   tmp2 = treemap(mem0,tmp1,fun)
//   mem3 = st mem0,new.left,tmp2
//   tmp4 = ld tree.right;
//   tmp5 = treemap(mem3,tmp4,fun)
//   mem6 = st mem3,new.right,tmp5
//   tmp7 = ld tree.val
//   tmp8 = fun(tmp7)
//   mem9 = st mem6,new.val,tmp8
//   return mem9,new
// }
//
// BUG:
//   mem3 produces final left field memory
//   tmp5 call push final left into treemap memory phi.  LEAKS NEW.
//   mem0 merges final-left of leaked new with a recursive new.  NEW IS NOW APPROXIMATED WITH FINAL-LEFT
//   mem3 IN ERROR storing final-left over final-left.
//
// This "escape" does not happen in the "value model" of the world, since there
// is no memory merge after the new.  Parser needs a way to merge the memory
// since it cannot track the pointer seperate from the memory... which means
// the main iter/gcp needs to tease apart the escaping situation.
//
// THREE "escape analysis" plans:
// - Local analysis only: post-memmerge/callepi/call/pre-memmerge; "new" not
//   stored in this region, not passed as an argument, so does not escape.
//   Bypass "new" around call.
// - Local graph peek inside of treemap; see Ret->Merge->Parm_Mem->Fun.  All
//   memories except "new" not modded, can bypass.  "tree" itself is loaded,
//   must be passed in.  "new" is local, so also bypasses recursive calls.
// - Forward flow analysis around any SESE region: tracks past uses & mods.
//   Annotate memory type with 0/r/w/new per-field starting from Fun, and
//   propagate to Ret.  Seperately CallEpi can observe memory is either not
//   touched, or new, or read-only, vs write - and bypass.





public class CallNode extends Node {
  int _rpc;                 // Call-site return PC
  boolean _unpacked;        // Call site allows unpacking a tuple (once)
  boolean _is_copy;         // One-shot flag set when inlining an entire single-caller-single-called 
  Parse  _badargs;          // Error for e.g. wrong arg counts or incompatible args
  public CallNode( boolean unpacked, Parse badargs, Node... defs ) {
    super(OP_CALL,defs);
    _rpc = BitsRPC.new_rpc(BitsRPC.ALL); // Unique call-site index
    _unpacked=unpacked;         // Arguments are typically packed into a tuple and need unpacking, but not always
    _badargs = badargs;
  }

  String xstr() { return ((is_dead() || is_copy()) ? "x" : "C")+"all#"+_rpc; } // Self short name
  String  str() { return xstr(); }       // Inline short name

  // Number of actual arguments
  int nargs() { return _defs._len-3; }
  // Actual arguments.
  Node arg( int x ) { return _defs.at(x+3); }
  void set_arg_reg(int idx, Node arg, GVNGCM gvn) { gvn.set_def_reg(this,idx+3,arg); }

          Node ctl() { return in(0); }
  public  Node fun() { return in(1); }
  public  Node mem() { return in(2); }
  //private void set_ctl    (Node ctl, GVNGCM gvn) {     set_def    (0,ctl,gvn); }
  private Node set_mem    (Node mem, GVNGCM gvn) { return set_def(2,mem,gvn); }
  private Node set_fun    (Node fun, GVNGCM gvn) { return set_def(1,fun,gvn); }
  public  void set_fun_reg(Node fun, GVNGCM gvn) { gvn.set_def_reg(this,1,fun); }
  public BitsFun fidxs(GVNGCM gvn) {
    Type tf = gvn.type(fun());
    return tf instanceof TypeFunPtr ? ((TypeFunPtr)tf).fidxs() : null;
  }

  // Clones during inlining all become unique new call sites.  The original RPC
  // splits into 2, and the two new children RPCs replace it entirely.  The
  // original RPC may exist in the type system for a little while, until the
  // children propagate everywhere.
  @Override @NotNull CallNode copy( boolean copy_edges, CallEpiNode unused, GVNGCM gvn) {
    CallNode call = (CallNode)super.copy(copy_edges,unused,gvn);
    ConNode old_rpc = gvn.con(TypeRPC.make(_rpc));
    int olen = old_rpc._uses._len;
    if( olen==0 ) old_rpc=null;
    else assert olen==1;               // Exactly a wired callsite
    call._rpc = BitsRPC.new_rpc(_rpc); // Children RPC
    Type oldt = gvn.unreg(this);       // Changes hash, so must remove from hash table
    _rpc = BitsRPC.new_rpc(_rpc);      // New child RPC for 'this' as well.
    gvn.rereg(this,oldt);              // Back on list
    // Swap out the existing old rpc users for the new.
    if( old_rpc != null ) {
      ConNode new_rpc = gvn.con(TypeRPC.make(_rpc));
      gvn.subsume(old_rpc,new_rpc);
    }
    return call;
  }

  @Override public Node ideal(GVNGCM gvn) {
    // If inlined, no further xforms.  The using Projs will fold up.
    if( is_copy() ) return null;
    // Dead, do nothing
    if( gvn.type(ctl())==Type.XCTRL ) return null;
    Node mem = mem();

    // When do I do 'pattern matching'?  For the moment, right here: if not
    // already unpacked a tuple, and can see the NewNode, unpack it right now.
    if( !_unpacked ) { // Not yet unpacked a tuple
      assert nargs()==1;
      Type tadr = gvn.type(arg(0));
      // Bypass a merge on the 1-arg input during unpacking
      if( tadr instanceof TypeMemPtr && mem instanceof MemMergeNode ) {
        int alias = ((TypeMemPtr)tadr)._aliases.abit();
        if( alias == -1 ) throw AA.unimpl(); // Handle multiple aliases, handle all/empty
        Node obj = ((MemMergeNode)mem).alias2node(alias);
        if( obj instanceof OProjNode && arg(0) instanceof ProjNode && arg(0).in(0) instanceof NewNode ) {
          NewNode nnn = (NewNode)arg(0).in(0);
          remove(_defs._len-1,gvn); // Pop off the NewNode tuple
          int len = nnn._defs._len;
          for( int i=1; i<len; i++ ) // Push the args; unpacks the tuple
            add_def( nnn.in(i));
          gvn.add_work(mem().in(1));
          _unpacked = true;     // Only do it once
          return this;
        }
      }
    }

    Node unk  = fun();          // Function epilog/function pointer
    // If the function is unresolved, see if we can resolve it now
    if( unk instanceof UnresolvedNode ) {
      FunPtrNode fun = resolve(gvn);
      if( fun != null )         // Replace the Unresolved with the resolved.
        return set_fun(fun,gvn);
    }

    return null;
  }

  // Pass thru all inputs directly - just a direct gather/scatter.  The gather
  // enforces SESE which in turn allows more precise memory and aliasing.  The
  // full scatter lets users decide the meaning; e.g. wired FunNodes will take
  // the full arg set but if the call is not reachable the FunNode will not
  // merge from that path.

  // TODO: Only uses a reachable selection of memory, so want the memory usage
  // to have a limited set of aliases.
  @Override public TypeTuple value(GVNGCM gvn) {
    Type[] ts = TypeAry.get(_defs._len);
    for( int i=0; i<_defs._len; i++ )
      ts[i] = gvn.type(in(i));
    

    // Compute a new Memory which is trimmed to everything recursively
    // reachable from all active display alias#s plus all args.  First check
    // that we can find our functions.
    BitsFun fidxs = fidxs(gvn);
    if( fidxs == null ) ts[1] = TypeFunPtr.GENERIC_FUNPTR;
    if( fidxs == null || // Might be e.g. ~Scalar
        fidxs.test(BitsFun.ALL) ||
        is_copy() )
      return TypeTuple.make(ts);

    // Here, I start with all alias#s from TMP args plus all function
    // closure#s and "close over" the set of possible aliases.
    TypeMem tmem = (TypeMem)ts[2];
    if( tmem == TypeMem.XMEM || tmem == TypeMem.EMPTY_MEM ) return TypeTuple.make(ts);
    // Set of aliases escaping into the function
    VBitSet abs = new VBitSet();
    // All fidxes in a flat iterable loop
    BitSet bs = fidxs.tree().plus_kids(fidxs);
    for( int fidx = bs.nextSetBit(0); fidx >= 0; fidx = bs.nextSetBit(fidx+1) ) {
      FunNode fun = FunNode.find_fidx(fidx);
      for( int alias : fun._closure_aliases )
        tmem.recursive_aliases(abs,alias); // Function closures always escape
    }
    // Now the set of pointers escaping via arguments
    for( int i=0; i<nargs(); i++ ) {
      Type targ = gvn.type(arg(i));
      if( targ instanceof TypeMemPtr )
        for( int alias : ((TypeMemPtr)targ)._aliases )
          tmem.recursive_aliases(abs,alias);
    }
    // Shortcut for primitives.  They are common and typically are not passed
    // any pointers, nor have any exciting closures.  Look for a single closure
    // of exactly the primitives.
    abs.clear(Env.STK_0._alias);
    if( abs.length()==0 ) {      // Nothing left?
      ts[2] = TypeMem.EMPTY_MEM; // Empty memory
      return TypeTuple.make(ts);
    }

    // Add all the aliases which can be reached from objects at the existing
    // aliases, recursively.
    TypeMem trez = tmem.trim_to_alias(abs);
    ts[2] = trez;
    return TypeTuple.make(ts);
  }

  // Given a list of actuals, apply them to each function choice.  If any
  // (!actual-isa-formal), then that function does not work and supplies an
  // ALL to the JOIN.  This is common for non-inlined calls where the unknown
  // arguments are approximated as SCALAR.  Lossless conversions are allowed as
  // part of a valid isa test.  As soon as some function returns something
  // other than ALL (because args apply), it supersedes other choices- which
  // can be dropped.

  // If more than one choice applies, then the choice with fewest costly
  // conversions are kept; if there is more than one then the join of them is
  // kept - and the program is not-yet type correct (ambiguous choices).
  public FunPtrNode resolve( GVNGCM gvn ) {
    BitsFun fidxs = fidxs(gvn);
    if( fidxs == null ) return null; // Might be e.g. ~Scalar
    // Need to resolve if we have above-center choices (overload choices) or an
    // Unresolved (which will be above-center during gcp()).
    if( !(fun() instanceof UnresolvedNode) && !fidxs.above_center() )
      return null;              // Sane as-is, just has multiple choices
    if( fidxs.test(BitsFun.ALL) ) return null;
    // Any function ptr, plus all of its children, are meet/joined.  This does
    // a tree-based scan on the inner loop.
    BitSet bs = fidxs.tree().plus_kids(fidxs);

    // Set of possible choices with fewest conversions
    class Data {
      private final int _xcvt, _fidx;
      private final boolean _unk;
      private final TypeStruct _formals;
      private Data( int x, boolean u, int f, TypeStruct ts ) { _xcvt = x; _unk=u; _fidx=f; _formals=ts; }
    }
    Ary<Data> ds = new Ary<>(new Data[0]);

    // For each function, see if the actual args isa the formal args.  If not,
    // toss it out.  Also count conversions, and keep the minimal conversion
    // function with all arguments known.
    outerloop:
    for( int fidx = bs.nextSetBit(0); fidx >= 0; fidx = bs.nextSetBit(fidx+1) ) {
      FunNode fun = FunNode.find_fidx(fidx);
      if( fun.nargs() != nargs() ) continue; // Wrong arg count, toss out
      TypeStruct formals = fun._tf._args;   // Type of each argument

      // Now check if the arguments are compatible at all, keeping lowest cost
      int xcvts = 0;             // Count of conversions required
      boolean unk = false;       // Unknown arg might be incompatible or free to convert
      for( int j=0; j<nargs(); j++ ) {
        Type actual = gvn.type(arg(j));
        Type tx = actual.join(formals.at(j));
        if( tx != actual && tx.above_center() ) // Actual and formal have values in common?
          continue outerloop;   // No, this function will never work; e.g. cannot cast 1.2 as any integer
        byte cvt = actual.isBitShape(formals.at(j)); // +1 needs convert, 0 no-cost convert, -1 unknown, 99 never
        if( cvt == 99 )         // Happens if actual is e.g. TypeErr
          continue outerloop;   // No, this function will never work
        if( cvt == -1 ) unk = true; // Unknown yet
        else xcvts += cvt;          // Count conversions
      }

      Data d = new Data(xcvts,unk,fidx,formals);
      ds.push(d);
    }

    // Toss out choices with strictly more conversions than the minimal
    int min = 999;              // Required conversions
    for( Data d : ds )          // Find minimal conversions
      if( !d._unk && d._xcvt < min ) // If unknown, might need more converts
        min = d._xcvt;
    for( int i=0; i<ds._len; i++ ) // Toss out more expensive options
      if( ds.at(i)._xcvt > min )   // Including unknown... which might need more converts
        ds.del(i--);

    // If this set of formals is strictly less than a prior acceptable set,
    // replace the prior set.  Similarly, if strictly greater than a prior set,
    // toss this one out.
    for( int i=0; i<ds._len; i++ ) {
      if( ds.at(i)._unk ) continue;
      TypeStruct ifs = ds.at(i)._formals;
      for( int j=i+1; j<ds._len; j++ ) {
        if( ds.at(j)._unk ) continue;
        TypeStruct jfs = ds.at(j)._formals;
        if( ifs.isa(jfs) ) ds.del(j--); // Toss more expansive option j
        else if( jfs.isa(ifs) ) { ds.del(i--); break; } // Toss option i
      }
    }

    if( ds._len==0 ) return null;   // No choices apply?  No changes.
    if( ds._len==1 )                // Return the one choice
      return FunNode.find_fidx(ds.pop()._fidx).ret().funptr();
    if( ds._len==fun()._defs._len ) return null; // No improvement
    // TODO: return shrunk list
    //return gvn.xform(new UnresolvedNode(ns.asAry())); // return shrunk choice list
    return null;
  }

  @Override public String err(GVNGCM gvn) {
    // Error#1: fail for passed-in unknown references
    for( int j=0; j<nargs(); j++ )
      if( arg(j).is_forward_ref() )
        return _badargs.forward_ref_err(((FunPtrNode)arg(j)).fun());

    Node fp = fun();      // Either function pointer, or unresolved list of them
    Type tfp = gvn.type(fp);
    if( !(tfp instanceof TypeFunPtr) )
      return _badargs.errMsg("A function is being called, but "+tfp+" is not a function type");
    Node xfp = fp instanceof UnresolvedNode ? fp.in(0) : fp;
    if( xfp instanceof FunPtrNode ) {
      FunPtrNode ptr = (FunPtrNode)xfp;
      int fidx = ptr.ret()._fidx;    // Reliably returns a fidx
      FunNode fun = FunNode.find_fidx(fidx);
      if( fp.is_forward_ref() ) // Forward ref on incoming function
        return _badargs.forward_ref_err(fun);

      // Error#2: bad-arg-count
      if( fun.nargs() != nargs() )
        return _badargs.errMsg("Passing "+nargs()+" arguments to "+(fun.xstr())+" which takes "+fun.nargs()+" arguments");

      // Error#3: Now do an arg-check
      TypeStruct formals = fun._tf._args; // Type of each argument
      for( int j=0; j<nargs(); j++ ) {
        Type actual = gvn.type(arg(j));
        Type formal = formals.at(j);
        if( !actual.isa(formal) ) // Actual is not a formal
          return _badargs.typerr(actual,formal);
      }
    }

    return null;
  }

  @Override public TypeTuple all_type() {
    return TypeTuple.make(Type.CTRL,
                          TypeFunPtr.GENERIC_FUNPTR,
                          TypeMem.ALL_MEM
                          );
  }
  @Override public int hashCode() { return super.hashCode()+_rpc; }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof CallNode) ) return false;
    CallNode call = (CallNode)o;
    return _rpc==call._rpc;
  }
  private boolean is_copy() { return _is_copy; }
  @Override public Node is_copy(GVNGCM gvn, int idx) { return is_copy() ? in(idx) : null; }
}
