package com.cliffc.aa.node;

import com.cliffc.aa.AA;
import com.cliffc.aa.Env;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.SB;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

// FunNode is a RegionNode; args point to all the known callers.  Zero slot is
// null, same as a C2 Region.  Args 1+ point to the callers control.  Before
// GCP/opto arg 1 points to ALL_CTRL as the generic unknown worse-case caller.
// ALL_CTRL is removed just prior to GCP, the precise call-graph is discovered,
// and calls are directly wired to the FunNode as part of GCP.  After GPC the
// call-graph is known precisely and is explicit in the graph.
//
// FunNodes are finite in count and are unique densely numbered, see BitsFun.
//
// Pointing to the FunNode are ParmNodes which are also PhiNodes; one input
// path for each known caller.  Zero slot points to the FunNode.  Arg1 points
// to the generic unknown worse-case caller (a type-specific ConNode with
// worse-case legit bottom type).  ParmNodes merge all input path types (since
// they are a Phi), and the call "loses precision" for type inference there.
// Parm#0 and up (NOT the zero-slot but the zero idx) are the classic function
// arguments; Parm#-1 is for the RPC and Parm#-2 is for memory.
//
// The function body points to the FunNode and ParmNodes like C2.
//
// RetNode points to the return control, memory, value and RPC.  EpilogNode
// points to the RetNode and is typed as a TypeFunPtr.  The TFP is used as a
// 1st-class function pointer and is carried through the program like a normal
// value.  Direct Calls will point to the Epilog directly.
// 
public class FunNode extends RegionNode {
  public String _name;          // Optional for anon functions; can be set later via bind()
  public TypeFunPtr _tf;        // FIDX, arg & ret types
  // Operator precedence; only set on top-level primitive wrappers.
  // -1 for normal non-operator functions and -2 for forward_decls.
  private final byte _op_prec;  // Operator precedence; only set on top-level primitive wrappers

  // Used to make the primitives at boot time
  public  FunNode(PrimNode prim) { this(prim._name,TypeFunPtr.make_new(prim._targs,prim._ret),prim.op_prec()); }
  public  FunNode(IntrinsicNewNode prim, Type ret) { this(prim._name,TypeFunPtr.make_new(prim._targs,ret),-1); }
  // Used to make copies when inlining/cloning function bodies
          FunNode(String name,TypeFunPtr tf) { this(name,tf,-1); }
  // Used to start an anonymous function in the Parser
  public  FunNode(Type[] ts) { this(null,TypeFunPtr.make_new(TypeTuple.make_args(ts),Type.SCALAR),-1); }
  // Used to forward-decl anon functions
          FunNode(String name) { this(name,TypeFunPtr.make_anon(),-2); }
  // Shared common constructor
  private FunNode(String name, TypeFunPtr tf, int op_prec) {
    super(OP_FUN);
    _name = name;
    assert tf.isa(TypeFunPtr.GENERIC_FUNPTR);
    assert TypeFunPtr.GENERIC_FUNPTR.dual().isa(tf);
    _tf = tf;
    _op_prec = (byte)op_prec;
    add_def(Env.ALL_CTRL);
    assert !tf.is_class();
    FUNS.setX(fidx(),this); // Track FunNode by fidx; assert single-bit fidxs
  }
  
  // Find FunNodes by fidx
  static Ary<FunNode> FUNS = new Ary<>(new FunNode[]{null,});
  public static FunNode find_fidx( int fidx ) { return fidx >= FUNS._len ? null : FUNS.at(fidx); }
  int fidx() { return _tf.fidx(); } // Asserts single-bit internally
  
  // Fast reset of parser state between calls to Exec
  private static int PRIM_CNT;
  public static void init0() { PRIM_CNT = FUNS._len; }
  public static void reset_to_init0() {
    FUNS.set_len(PRIM_CNT);
    for( int i=2; i<PRIM_CNT; i++ ) {
      FunNode fun = FUNS.at(i);
      if( fun != null && fun.fidx() != i ) { // Cloned primitives get renumbered, so renumber back
        fun._tf = fun._tf.make_fidx(i);
        fun.ret()._fidx = i;
      }
    }
  }

  // Short self name
  @Override String xstr() { return name(); }
  // Inline longer info
  @Override public String str() { return is_forward_ref() ? xstr() : _tf.str(null); }
  // Name from fidx alone
  static String name( int fidx ) {
    FunNode fun = find_fidx(fidx);
    return fun == null ? ""+fidx : fun.name();
  }
  // Name from FunNode
  String name() {
    return is_forward_ref()
      ? "?"+_name
      : (_op_prec >= 0 ? "{"+_name+"}" : _name+"={->}");
  }
  // Can return nothing, or "name" or "[name0,name1,name2...]" or "[35]"
  public static SB names(BitsFun fidxs, SB sb ) {
    int fidx = fidxs.abit();
    if( fidx >= 0 ) return sb.p(name(fidx));
    int cnt=0;
    for( Integer ii : fidxs ) {
      if( ++cnt==5 ) break;
      sb.p(name(ii)).p(fidxs.above_center()?'+':',');
    }
    if( cnt>=5 ) sb.p("...");
    return sb;
  }

  // Debug only: make an attempt to bind name to a function
  public void bind( String tok ) {
    assert _name==null || _name.equals(tok); // Attempt to double-bind
    _name = tok;
  }

  @Override Node copy(GVNGCM gvn) { throw AA.unimpl(); } // Gotta make a new FIDX

  // True if no future unknown callers.
  boolean has_unknown_callers() { return in(1) == Env.ALL_CTRL; }
  // Argument type
  Type targ(int idx) {
    return idx == -1 ? TypeRPC.ALL_CALL :
      (idx == -2 ? TypeMem.MEM : _tf.arg(idx));
  }
  int nargs() { return _tf._args._ts.length; }
  
  // ----
  @Override public Node ideal(GVNGCM gvn) {
    // Generic Region ideal
    Node n = super.ideal(gvn);
    if( n!=null ) return n;

    if( gvn._small_work ) { // Only doing small-work now
      gvn.add_work2(this);  // Maybe want to inline later
      return null;
    }
      
    // Type-specialize as-needed
    if( is_forward_ref() ) return null;
    ParmNode rpc_parm = rpc();
    if( rpc_parm == null ) return null; // Single caller const-folds the RPC, but also inlines in CallNode
    ParmNode[] parms = new ParmNode[nargs()];
    RetNode ret = split_callers_gather(gvn,parms);
    if( ret == null ) return null;

    // Major Call-Graph layout check
    String err=null;
    assert (err=ret.check())==null; // Check all outgoing wired CallEpis for sanity.
    assert (err=    check(gvn,ret,rpc_parm,true))==null; // Check all incoming paths for being both alive and wired. 
    
    // Look for appropriate type-specialize callers
    FunNode fun = type_special(gvn,ret,parms);
    if( fun == null ) { // No type-specialization to do
      // Large code-expansion allowed; can inline for other reasons
      fun = split_size(gvn,ret,parms);
      if( fun == null ) return null;
    }
    // Split the callers according to the new 'fun'.
    return split_callers(gvn,ret,fun);
  }

  // Gather the ParmNodes into an array.  Return null if any input path is dead
  // (would rather fold away dead paths before inlining).  Return null if there
  // is only 1 path.  Return null if any actuals are not formals.
  private RetNode split_callers_gather( GVNGCM gvn, ParmNode[] parms ) {
    for( int i=1; i<_defs._len; i++ ) if( gvn.type(in(i))==Type.XCTRL ) return null;
    if( _defs._len <= 2 ) return null; // No need to split callers if only 1

    // Gather the ParmNodes and the RetNode.  Ignore other (control) uses
    RetNode ret = null;
    for( Node use : _uses )
      if( use instanceof ParmNode ) {
        ParmNode parm = (ParmNode)use;
        if( parm._idx >= 0 ) // Skip memory, rpc
          parms[parm._idx] = parm;
      } else if( use instanceof RetNode ) { assert ret==null || ret==use; ret = (RetNode)use; }
    return ret;                 // return (or null if dead)
  }

  // Visit all ParmNodes, looking for unresolved call uses that can be improved
  // by type-splitting
  private int find_type_split_index( GVNGCM gvn, ParmNode[] parms ) {
    assert has_unknown_callers(); // Only overly-wide calls.
    for( ParmNode parm : parms ) // For all parms
      if( parm != null )         //   (some can be dead)
        for( Node call : parm._uses ) // See if a parm-user needs a type-specialization split
          if( call instanceof CallNode &&
              ((CallNode)call).fun() instanceof UnresolvedNode ) { // Call overload not resolved
            Type t0 = gvn.type(parm.in(1));            // Generic type in slot#1
            for( int i=2; i<parm._defs._len; i++ ) {   // For all other inputs
              Type tp = gvn.type(parm.in(i));
              if( tp.above_center() ) continue;        // This parm input is in-error
              Type ti = tp.widen();                    // Get the widen'd type
              assert ti.isa(t0);                       // Must be isa-generic type, or else type-error
              if( ti != t0 ) return i; // Sharpens?  Then splitting here should help
            }
            // Else no split will help this call, look for other calls to help
          }
    return -1; // No unresolved calls; no point in type-specialization
  }
    
  private Type[] find_type_split( GVNGCM gvn, ParmNode[] parms ) {
    assert has_unknown_callers(); // Only overly-wide calls.
    // Look for splitting to help an Unresolved Call.
    int idx = find_type_split_index(gvn,parms);
    if( idx != -1 ) {           // Found; split along a specific input path using widened types
      Type[] sig = new Type[parms.length];
      for( int i=0; i<parms.length; i++ )
        sig[i] = parms[i]==null ? Type.SCALAR : gvn.type(parms[i].in(idx)).widen();
      return sig;
    }

    // Look for splitting to help a field Load from an unspecialized type.
    // Split ~Scalar,~nScalar,~Oop,~nOop, ~Struct,~nStruct into ~Struct and add
    // fields discovered.
    boolean progress=false;
    Type[] sig = new Type[parms.length];
    for( int i=0; i<parms.length; i++ ) { // For all parms
      Node parm = parms[i];
      if( parm == null ) { sig[i]=Type.SCALAR; continue; } // (some can be dead)
      Type tp = gvn.type(parm);
      for( Node puse : parm._uses ) { // See if a parm-user needs a load specialization
        Type rez = find_load_use(puse,tp);
        if( rez != tp ) { progress = true; tp = rez; }
      }
      sig[i] = tp;
    }
    if( !progress ) return null;
    throw AA.unimpl();
  }

  private static Type find_load_use(Node puse, Type tp) {
    if( !tp.isa(TypeStruct.GENERIC) ) return tp;
    if( puse instanceof CastNode ) 
      throw AA.unimpl();
    if( puse instanceof LoadNode ) 
      throw AA.unimpl();
    return tp;                  // Unknown use, can ignore
  }

  
  // Look for type-specialization inlining.  If any ParmNode has an unresolved
  // Call user, then we'd like to make a clone of the function body (in least
  // up to getting all the Unresolved functions to clear out).  The specialized
  // code uses generalized versions of the arguments, where we only specialize
  // on arguments that help immediately.
  //
  // Same argument for field Loads from unspecialized values.
  private FunNode type_special( GVNGCM gvn, RetNode ret, ParmNode[] parms ) {
    if( !has_unknown_callers() ) return null; // Only overly-wide calls.
    Type[] sig = find_type_split(gvn,parms);
    if( sig == null ) return null; // No unresolved calls; no point in type-specialization

    // If Parm has unresolved calls, we want to type-specialize on its
    // arguments.  Call-site #1 is the most generic call site for the parser
    // (all Scalar args).  Peel out 2nd call-site args and generalize them.
    
    // Make a new function header with new signature
    TypeTuple args = TypeTuple.make_args(sig);
    assert args.isa(_tf._args);
    if( args == _tf._args ) return null; // Must see improvement
    // Make a prototype new function header split from the original.
    int oldfidx = fidx();
    FunNode fun = new FunNode(_name,TypeFunPtr.make(BitsFun.make_new_fidx(oldfidx),args,_tf._ret));
    // Renumber the original as well; the original _fidx is now a *class* of 2
    // fidxs.  Each FunNode fidx is only ever a constant.
    _tf = _tf.make_new_fidx(oldfidx);
    int newfidx = fidx();
    ret._fidx = newfidx;        // Renumber in the old RetNode
    gvn.add_work(ret);  gvn.add_work(ret.funptr());
    FUNS.setX(newfidx,this);    // Track FunNode by fidx
    // Look in remaining paths and decide if they split or stay
    Node xctrl = gvn.con(Type.XCTRL);
    for( int j=2; j<_defs._len; j++ ) {
      boolean split=true;
      for( int i=0; i<parms.length; i++ ) {
        if( parms[i]==null ) continue;
        Type tp = gvn.type(parms[i].in(j));
        if( tp.above_center() || !tp.isa(sig[i]) )
          { split=false; break; }
      }
      fun.add_def(split ? in(j) : xctrl);
    }
    return fun;
  }

  // Split a single-use copy (e.g. fully inline) if the function is "small
  // enough".  Include anything with just a handful of primitives, or a single
  // call, possible with a single if.
  private FunNode split_size( GVNGCM gvn, RetNode ret, ParmNode[] parms ) {
    if( _defs._len <= 1 ) return null; // No need to split callers if only 2
    int[] cnts = new int[OP_MAX];
    BitSet bs = new BitSet();
    Ary<Node> work = new Ary<>(new Node[1],0);
    work.add(this);             // Prime worklist
    while( work._len > 0 ) {    // While have work
      Node n = work.pop();      // Get work
      if( bs.get(n._uid) ) continue; // Already visited?
      bs.set(n._uid);           // Flag as visited
      int op = n._op;           // opcode
      if( op == OP_FUN  && n       != this ) continue; // Call to other function, not part of inlining
      if( op == OP_PARM && n.in(0) != this ) continue; // Arg  to other function, not part of inlining
      if( n != ret )            // Except for the RetNode
        work.addAll(n._uses);   // Visit all uses also
      if( op == OP_CALL ) {     // Call-of-primitive?
        Node n1 = ((CallNode)n).fun();
        Node n2 = n1 instanceof UnresolvedNode ? n1.in(0) : n1;
        if( n2 instanceof FunPtrNode &&
            ((FunPtrNode)n2).ret().val() instanceof PrimNode )
          op = OP_PRIM;         // Treat as primitive for inlining purposes
      }
      cnts[op]++;               // Histogram ops
    }
    assert cnts[OP_FUN]==1 && cnts[OP_RET]==1;
    assert cnts[OP_SCOPE]==0 && cnts[OP_TMP]==0;
    assert cnts[OP_REGION] <= cnts[OP_IF];

    // Pick which input to inline.  Only based on having some constant inputs
    // right now.
    int m=-1, mncons = -1;
    for( int i=has_unknown_callers() ? 2 : 1; i<_defs._len; i++ ) {
      int ncon=0;
      for( ParmNode parm : parms )
        if( parm != null &&     // Some can be dead
            gvn.type(parm.in(i)).is_con() )
          ncon++;
      if( ncon > mncons ) { mncons = ncon; m = i; }
    }

    // Specifically ignoring constants, parms, phis, rpcs, types,
    // unresolved, and casts.  These all track & control values, but actually
    // do not generate any code.
    if( cnts[OP_CALL] > 1 || // Careful inlining more calls; leads to exponential growth
        cnts[OP_IF  ] > 1+mncons || // Allow some trivial filtering to inline
        cnts[OP_PRIM] > 6 )  // Allow small-ish primitive counts to inline
      return null;

    // Make a prototype new function header.  No generic unknown caller
    // in slot 1.  The one inlined call in slot 'm'.
    // Make a prototype new function header.
    int oldfidx = fidx();
    FunNode fun = new FunNode(_name,_tf.make_new_fidx(oldfidx));
    // Renumber the original as well; the original _fidx is now a *class* of 2
    // fidxs.  Each FunNode fidx is only ever a constant.
    _tf = _tf.make_new_fidx(oldfidx);
    ret._fidx = fidx();         // Renumber in the old FunPtrNode
    gvn.add_work(ret);  gvn.add_work(ret.funptr());
    FUNS.setX(fidx(),this);     // Track FunNode by fidx
    fun.pop();                  // Remove junk ALL_CTRL input
    Node top = gvn.con(Type.XCTRL);
    for( int i=1; i<_defs._len; i++ )
      fun.add_def(i==m ? in(i) : top);
    return fun;
  }

  // Clone the function body, and split the callers of 'this' into 2 sets; one
  // for the old and one for the new body.  The new function may have a more
  // refined signature, and perhaps no unknown callers.  
  private Node split_callers( GVNGCM gvn, RetNode ret, FunNode fun) {
    // Clone the function body
    HashMap<Node,Node> map = new HashMap<>();
    Ary<Node> work = new Ary<>(new Node[1],0);
    map.put(this,fun);
    work.addAll(_uses);         // Prime worklist
    while( work._len > 0 ) {    // While have work
      Node n = work.pop();      // Get work
      if( map.get(n) != null ) continue; // Already visited?
      int op = n._op;           // opcode
      if( op == OP_FUN  && n       != this ) continue; // Call to other function, not part of inlining
      if( op == OP_PARM && n.in(0) != this ) continue; // Arg  to other function, not part of inlining
      if( op == OP_CALLEPI && n.in(0) == ret ) continue; // Wired call 
      assert n._uid < fun._uid; // Recursive calls will call 'fun' directly
      map.put(n,n.copy(gvn));  // Make a blank copy with no edges and map from old to new
      if( n != ret )           // Do not walk past the RetNode
        work.addAll(n._uses);  // Visit all uses also
    }

    // Fill in edges.  New Nodes point to New instead of Old; everybody
    // shares old nodes not in the function (and not cloned).  The
    // FunNode & Parms only get the matching slice of args.
    Node  any = gvn.con(Type.XCTRL);
    Node dany = gvn.con(Type.XSCALAR);
    for( Node n : map.keySet() ) {
      Node c = map.get(n);
      if( n instanceof ParmNode && n.in(0) == this ) {  // Leading edge ParmNodes
        c.add_def(map.get(n.in(0))); // Control
        //for( int j=1; j<_defs._len; j++ ) // Get the new parm path or ~Scalar according to split
        //  c.add_def( fun.in(j)==any ? dany : n.in(j) );
        // Slot#1 for a type-split gets the new generic type from the new signature.
        // Slot#1 for other live splits gets from the old parm inputs.
        int idx = ((ParmNode)n)._idx;
        Node x = has_unknown_callers() 
          // type-split; maybe more unknown callers... so slot#1 remains generic
          ? gvn.con(fun.targ(idx))             // Generic arg#1
          : (fun.in(1)==any ? dany : n.in(1)); // Just another argument
        c.add_def(x); 
        for( int j=2; j<_defs._len; j++ ) // Get the new parm path or null according to split
          c.add_def( fun.in(j)==any ? dany : n.in(j) );
        // Update default type to match signature
        if( idx >= 0 ) ((ParmNode)c)._default_type = fun.targ(idx);
      } else if( n != this ) {  // Interior nodes
        for( Node def : n._defs ) {
          // Map old to new.  The epilog choices will be updated further below.
          Node newdef = map.get(def);
          c.add_def(newdef==null ? def : newdef);
        }
      }
    }
    if( dany._uses._len==0 ) gvn.kill(dany);
    // Kill split-out path-ins to the old code.  If !_all_callers_known then
    // always keep slot#1, otherwise kill slots being taken over by the new
    // function.
    for( int j=has_unknown_callers() ? 2 : 1; j<_defs._len; j++ )
      if( fun.in(j)!=any )  // Path split out?
        set_def(j,any,gvn); // Kill incoming path on old FunNode
    
    // Put all new nodes into the GVN tables and worklists
    for( Map.Entry<Node,Node> e : map.entrySet() ) {
      Node nn = e.getValue();         // New node
      Type ot = gvn.type(e.getKey()); // Generally just copy type from original nodes
      if( nn instanceof ParmNode && ((ParmNode)nn)._idx==-1 )
        ot = nn.all_type();     // Except the new RPC, which has new callers
      else if( nn instanceof QNode )
        ot = fun._tf;           // Except the new Epilog, which matches a new function
      gvn.rereg(nn,ot);
    }
    assert !ret.is_dead();      // Not expecting this to be dead already

    
    // Repoint all uses of the original Epilog to an Unresolved choice of
    // the old and new functions and let the Calls resolve individually.
    RetNode newret = (RetNode)map.get(ret);
    newret._fidx = fun.fidx();
    FunPtrNode new_funptr = (FunPtrNode)gvn.xform(new FunPtrNode(newret));
    FunPtrNode old_funptr = ret.funptr();

    if( _tf._args != fun._tf._args ) { // Split-for-type, possible many future callers

      UnresolvedNode new_unr = new UnresolvedNode();
      new_unr.add_def(new_funptr);
      gvn.init(new_unr);

      // Direct all uses of the old function pointer to the unresolved.
      // Leaves old_funptr hanging.
      gvn.replace(old_funptr,new_unr);
      // Now hook old_funptr.
      gvn.add_def(new_unr,old_funptr);
      // Update Unresolved type, since otherwise it starts very low
      gvn.setype(new_unr,new_unr.value(gvn));

      // If we are pre-opto, we can leave the new function unwired.  After opto
      // we must wire all calls, because opto assumes a full CFG.
      if( gvn._opt_mode == 2 )
        throw com.cliffc.aa.AA.unimpl();
    }

    // Wire matching CallEpis to the new RetNode.
    ParmNode rpc_parm = rpc();
    ret.keep(); // Can go dead, if a generic functions last-use is being inlined
    for( int k=0; k<ret._uses._len; k++ ) {
      Node cepi = ret._uses.at(k); // Find all Calls to the original function
      if( cepi instanceof CallEpiNode ) {
        CallNode call = ((CallEpiNode)cepi).call(); // Matching call
        int rpc = call._rpc;    // Find the original call RPC
        // Find the matching path for this RPC.
        for( int i=1; i<rpc_parm._defs._len; i++ ) { // Find matching original path
          TypeRPC trpc = (TypeRPC)gvn.type(rpc_parm.in(i));
          if( trpc != TypeRPC.ALL_CALL && trpc.rpc()==rpc ) { // RPC matches?
            // Is this path dead in the original (and live in the clone)?
            if( gvn.type(in(i))==Type.XCTRL ) {
              assert gvn.type(fun.in(i))==Type.CTRL;
              if( call.fun()==old_funptr )
                call.set_fun_reg(new_funptr,gvn);
              // Then re-wire this CallEpi to the new RetNode
              for( int j=1; j<cepi._defs._len; j++ )
                if( cepi.in(j)==ret ) {
                  gvn.set_def_reg(cepi,j,newret);
                  k--;        // Must rerun same 'k' since removed use
                  break;
                }
            }
            break;
          }
        }
      }
    }
    ret.unkeep(gvn);

    // There are new some cloned new Calls; these point to some old functions
    // which might believe "all_calls_known" - which excludes these new Calls.
    // The situation is still theoretically correct: the original Calls all
    // split into 2 non-overlapping sets: calls from the new Calls and those
    // left behind coming from the old Calls.  Update the old functions with
    // these new Calls as-needed.  If has_unknown_callers(), the old function
    // is prepared to handle unexpected new Calls already.  If its not true,
    // then immediately wire up the new Call, add the new RPC input and correct
    // all types.
    if( !is_dead() && !has_unknown_callers() ) {
      for( Node c : map.values() ) {
        if( c instanceof CallNode ) { // For all cloned Calls
          CallNode call = (CallNode)c;
          for( int fidx : call.fidxs(gvn) ) { // For all possible targets of the Call
            FunNode oldfun = FunNode.find_fidx(fidx);
            assert !oldfun.is_dead();
            if( !oldfun.has_unknown_callers() ) {
              throw com.cliffc.aa.AA.unimpl();
              //gvn.add_work(oldfun);
              //Node x = ((CallNode)c).callepi().wire(gvn,call,oldfun,oldfun.ret());
              //assert x != null;
              //ParmNode rpc = oldfun.rpc();
              //if( rpc != null ) // Can be null if there is a single return point, which got constant-folded
              //  gvn.setype(rpc,rpc.value(gvn));
              //FunPtrNode oldepi = oldfun.funptr();
              //gvn.setype(oldepi,oldepi.value(gvn));
            }
          }
        }
      }
    }
    
    // Major Call-Graph layout check
    String err=null;
    assert ret.is_dead() || (err=   ret.check())==null; // Check all outgoing wired CallEpis for sanity.
    assert                  (err=newret.check())==null; // Check all outgoing wired CallEpis for sanity.
    assert ret.is_dead() || (err=       check(gvn,   ret,    rpc(),false))==null; // Check all incoming paths for being both alive and wired.
    assert                  (err=fun.   check(gvn,newret,fun.rpc(),false))==null; // Check all incoming paths for being both alive and wired.
    
    // TODO: Hook with proper signature into ScopeNode under an Unresolved.
    // Future calls may resolve to either the old version or the new.
    return this;
  }

  // Compute value from inputs.  Simple meet over inputs.
  @Override public Type value(GVNGCM gvn) {
    // Will be an error eventually, but act like its executed so the trailing
    // EpilogNode gets visited during GCP
    if( is_forward_ref() ) return Type.CTRL;
    return super.value(gvn);
  }

  // True if this is a forward_ref
  @Override public boolean is_forward_ref() { return _op_prec==-2; }
  ParmNode parm( int idx ) {
    for( Node use : _uses )
      if( use instanceof ParmNode && ((ParmNode)use)._idx==idx )
        return (ParmNode)use;
    return null;
  }
  public ParmNode rpc() { return parm(-1); }
  public RetNode ret() {
    for( Node use : _uses )
      if( use instanceof RetNode )
        return (RetNode)use;
    return null;
  }

  @Override public int hashCode() { return super.hashCode()+_tf.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !(o instanceof FunNode) ) return false;
    FunNode fun = (FunNode)o;
    return _tf == fun._tf;
  }
  @Override public byte op_prec() { return _op_prec; }

  String check(GVNGCM gvn, RetNode ret, ParmNode rpc_parm, boolean clean) {
    // Check all incoming paths for being both alive and wired. 
    for( int i=1; i<_defs._len; i++ ) {
      if( gvn.type(_defs.at(i))!=Type.CTRL ) { // Dead paths already cleared out coming into inlining
        assert !clean;                         // But inlining makes some dead paths
        continue;
      }
      TypeRPC trpc = (TypeRPC)gvn.type(rpc_parm.in(i));
      if( trpc == TypeRPC.ALL_CALL ) continue; // Skip the unknown caller
      int rpc = trpc.rpc(), found=0;
      // Must exist a wired CallEpi/Call pair matching this incoming RPC.
      for( Node cepi : ret._uses )
        if( cepi instanceof CallEpiNode &&
            ((CallEpiNode)cepi).call()._rpc == rpc )
          { found = 1; break; }
      if( found==0 ) return "Call RPC#"+rpc+" is wired, but no matching Call/CallEpi is wired";
    }
    return null;
  }
}
