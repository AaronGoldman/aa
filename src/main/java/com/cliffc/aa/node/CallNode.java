package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.type.*;

// See FunNode.  Control is not required for an apply but inlining the function
// body will require it; slot 0 is for Control.  Slot 1 is a function value - a
// Node with a TypeFun or a TypeUnion of TypeFuns.  Slots 2+ are for args.
//
// When the function simplifies to a single TypeFun, the Call can inline.
// Otherwise the TypeUnion lists a bunch of function pointers are allowed here.
//
// Call-inlining can happen anytime we have a known function pointer, and
// might be several known function pointers - we are inlining the type analysis
// and not the execution code.  For this kind of inlining we replace the
// CallNode with a call-site specific Epilog, move all the CallNode args to
// the ParmNodes just like the Fun/Parm is a Region/Phi.  The call-site index
// is just like a ReturnPC value on a real machine; it dictates which of
// several possible returns apply... and can be merged like a PhiNode

public class CallNode extends Node {
  private static int RPC=1; // Call-site return PC
  int _rpc;         // Call-site return PC
  private boolean _unpacked;    // Call site allows unpacking a tuple (once)
  boolean _wired;       // Args wired to the single unique function
  private boolean _inlined;     // Inlining a call-site is a 2-stage process; function return wired to the call return
  private Type   _cast_ret;     // Return type has been up-casted
  private Parse  _cast_P;       // Return type cast fail message
  private Parse  _badargs;      // Error for e.g. wrong arg counts or incompatible args
  public CallNode( boolean unpacked, Parse badargs, Node... defs ) { super(OP_CALL,defs); _rpc=RPC++; _unpacked=unpacked; _badargs = badargs; }

  String xstr() { return "Call#"+_rpc; } // Self short name
  String  str() { return xstr(); }       // Inline short name

  // Fast reset of parser state between calls to Exec
  private static int PRIM_RPC; // Primitives count of call-sites
  public static void init0() { PRIM_RPC=RPC; }
  public static void reset_to_init0() { RPC = PRIM_RPC; }

  @Override public Node is_copy(GVNGCM gvn, int idx) { return _inlined ? in(idx) : null; }

  // Number of actual arguments
  int nargs() { return _defs._len-2; }
  // Actual arguments.
  Node arg( int x ) { return _defs.at(x+2); }
  
  // Clones during inlining all become unique new call sites
  @Override Node copy() {
    CallNode call = super.copy();
    call._rpc = RPC++;
    return call;
  }
  
  @Override public Node ideal(GVNGCM gvn) {
    if( skip_ctrl(gvn) ) return this;
    // If an inline is in-progress, no other opts and this node will go dead
    if( _inlined ) return null;
    // If an upcast is in-progress, no other opts until it finishes
    if( _cast_ret !=null ) return null;

    // When do i do 'pattern matching'?  For the moment, right here: if not
    // already unpacked a tuple, and can see the NewNode, unpack it right now.
    if( !_unpacked ) { // Not yet unpacked a tuple
      assert nargs()==1;
      Node nn = arg(0);
      Type tn = gvn.type(nn);
      if( tn instanceof TypeTuple ) {
        TypeTuple tt = (TypeTuple)tn;
        // Either all the edges from a NewNode (for non-constants), or all the
        // constants types from a constant Tuple from a ConNode
        assert nn instanceof NewNode || tt.is_con();
        int len = nn instanceof NewNode ? nn._defs._len-1 : tt._ts.length;
        pop();  gvn.add_work(nn);  // Pop off the NewNode/ConNode tuple
        for( int i=0; i<len; i++ ) // Push the args; unpacks the tuple
          add_def( nn instanceof NewNode ? nn.in(i+1) : gvn.con(tt.at(i)) );
        _unpacked = true;       // Only do it once
        return this;
      }
    }

    // Type-checking a function; requires 2 steps, one now, one in the
    // following data Proj from the worklist.
    Node unk  = in(1);          // Function epilog/function pointer
    if( unk instanceof TypeNode ) {
      TypeNode tn = (TypeNode)unk;
      TypeTuple t_funptr = (TypeTuple)tn._t;
      assert t_funptr.is_fun_ptr();
      TypeFun tf = t_funptr.get_fun();
      set_def(1,tn.in(1),gvn);
      for( int i=0; i<nargs(); i++ ) // Insert casts for each parm
        set_def(i+2,gvn.xform(new TypeNode(tf._ts.at(i),arg(i),tn._error_parse)),gvn);
      _cast_ret = tf._ret;       // Upcast return results
      _cast_P = tn._error_parse; // Upcast failure message
      return this;
    }

    // If the function is unresolved, see if we can resolve it now
    if( unk instanceof UnresolvedNode ) {
      Node fun = ((UnresolvedNode)unk).resolve(gvn,this);
      if( fun != null ) { set_def(1,fun,gvn); return this; }
    }

    // Unknown function(s) being called
    if( !(unk instanceof EpilogNode) )
      return null;
    EpilogNode epi = (EpilogNode)unk;
    Node    rez = epi.val ();
    FunNode fun = epi.fun ();

    // Arg counts must be compatible
    if( fun._tf.nargs() != nargs() )
      return null;

    // Single choice; insert actual conversions as needed
    TypeTuple formals = fun._tf._ts;
    for( int i=0; i<nargs(); i++ ) {
      Type formal = formals.at(i);
      Type actual = gvn.type(arg(i));
      byte xcvt = actual.isBitShape(formal);
      if( xcvt == 99 ) return null;
      if( xcvt == -1 ) return null;       // Wait for call args to resolve
      if( xcvt == 1 ) {
        PrimNode cvt = PrimNode.convert(arg(i),actual,formal);
        if( cvt.is_lossy() ) throw new IllegalArgumentException("Requires lossy conversion");
        set_def(i+2,gvn.xform(cvt),gvn); // set the converted arg
      }
    }

    // If this is a forward-ref we have no body to inline
    if( epi.is_forward_ref() )
      return null;

    // Check for several trivial cases that can be fully inlined immediately.
    // Check for zero-op body (id function)
    if( rez instanceof ParmNode && rez.in(0) == fun ) return inline(gvn,arg(0));
    // Check for constant body
    if( rez instanceof ConNode ) return inline(gvn,rez);

    // Check for a 1-op body using only constants or parameters
    boolean can_inline=true;
    for( Node parm : rez._defs )
      if( parm != null && parm != fun &&
          !(parm instanceof ParmNode && parm.in(0) == fun) &&
          !(parm instanceof ConNode) )
        can_inline=false;       // Not trivial
    if( can_inline ) {
      Node irez = rez.copy();   // Copy the entire function body
      for( Node parm : rez._defs )
        irez.add_def((parm instanceof ParmNode && parm.in(0) == fun) ? arg(((ParmNode)parm)._idx) : parm);
      return inline(gvn,gvn.xform(irez));  // New exciting replacement for inlined call
    }

    // If this is a primitive, we never change the function header via inlining the call
    assert fun.in(1)._uid!=0;
    assert fun._tf.nargs() == nargs();
    // If the single function callers are known, then we are already wired up.
    // Happens at the transition (!known -> known) or anytime a wired CallNode
    // is cloned.  If we clone an unwired Call into a split/inline with
    // a single known caller, we can inline.
    if( !_wired && fun._callers_known &&
        wire(gvn,fun) ) return this;

    // TODO: if the function has this as the sole caller, can "inline"
    // by wiring the call's return up now.
    
    //// Flag the Call as is_copy;
    //// Proj#0 is RPC to return from the function back to here.
    //// Proj#1 is a new CastNode on the tf._ret to regain precision
    //// All other slots are killed.
    //for( int i=2; i<_defs._len; i++ ) set_def(i,null,gvn);
    //Node rpc = gvn.xform(new RPCNode(epi,epi,_rpc));
    //set_def(0,rpc,gvn);
    //// TODO: Use actual arg types to regain precision
    //return inline(gvn,gvn.xform(new CastNode(rpc,epi,fun._tf._ret)));
    return null;
  }

  // Wire the call args to a known function, letting the function have precise
  // knowledge of its callers and arguments.
  
  // Leave the Call in the graph - making the graph "a little odd" - double
  // CTRL users - once for the call, and once for the function being called.
  boolean wire(GVNGCM gvn, FunNode fun) {
    for( Node arg : fun._uses ) {
      if( arg.in(0) == fun && arg instanceof ParmNode ) {
        int idx = ((ParmNode)arg)._idx; // Argument number, or -1 for rpc
        if( idx != -1 &&
            (idx >= nargs() || !gvn.type(arg(idx)).isa(fun._tf.arg(idx))) )
          return false;         // Illegal args?
      }
    }
    
    // Inline the call site now.
    // This is NOT inlining the function body, just the call site.
    _wired = true;
    
    // Add an input path to all incoming arg ParmNodes from the Call.  Cannot
    // assert finding all args, because dead args may already be removed - and
    // so there's no Parm/Phi to attach the incoming arg to.
    for( Node arg : fun._uses ) {
      if( arg.in(0) == fun && arg instanceof ParmNode ) {
        int idx = ((ParmNode)arg)._idx; // Argument number, or -1 for rpc
        assert idx<nargs();
        Node actual = idx==-1 ? gvn.con(TypeRPC.make(_rpc)) : arg(idx);
        gvn.add_def(arg,actual);
      }
    }
    // Add Control for this path.  Sometimes called from fun.Ideal() (because
    // inlining), sometimes called by Epilog when it discovers all callers
    // known.
    if( gvn.touched(fun) ) gvn.add_def(fun,in(0)); 
    else                   fun.add_def(in(0));
    return true;
  }
  
  // Inline to this Node.
  private Node inline( GVNGCM gvn, Node rez ) {
    gvn.add_work(in(0));        // Major graph shrinkage; retry parent as well
    set_def(1,rez,gvn);
    _inlined = true;            // Allow data projection to find new body
    return this;
  }

  @Override public Type value(GVNGCM gvn) {
    Node fun = in(1);
    Type t = gvn.type(fun);
    if( !_inlined ) {           // Inlined functions just pass thru & disappear
      if( fun instanceof UnresolvedNode ) {
        // Might be forced to take the worst choice, based on args.  Until the
        // args settle out must be conservative.
        t = TypeErr.ANY;
        for( Node epi : fun._defs ) {
          Type t_unr = value1(gvn,gvn.type(epi));
          t = t.meet(t_unr);    // JOIN of choices
        }
      } else {                  // Single resolved target
        t = value1(gvn,t);      // Check args
      }
    }

    // Return {control,value} tuple.
    return TypeTuple.make(gvn.type(in(0)),t);
  }

  // Cannot return the functions return type, unless all args are compatible
  // with the function(s).  Arg-check.
  private Type value1( GVNGCM gvn, Type t ) {
    if( t instanceof TypeErr ) return t;
    assert t.is_fun_ptr();
    TypeTuple tepi = (TypeTuple)t;
    Type    tctrl=         tepi.at(0);
    Type    tval =         tepi.at(1);
    TypeRPC trpc =(TypeRPC)tepi.at(2);
    TypeFun tfun =(TypeFun)tepi.at(3);
    if( tctrl == Type.XCTRL ) return TypeErr.ANY; // Function will never return
    assert tctrl==Type.CTRL;      // Function will never return?
    if( t.is_forward_ref() ) return tfun.ret(); // Forward refs do no argument checking
    if( tfun.nargs() != nargs() )
      return nargerr(tfun);
    // Now do an arg-check
    TypeTuple formals = tfun._ts;   // Type of each argument
    Type terr = TypeErr.ANY;        // No errors (yet)
    for( int j=0; j<nargs(); j++ ) {
      Type actual = gvn.type(arg(j));
      Type formal = formals.at(j);
      if( actual instanceof TypeErr && !actual.above_center() ) { // Actual is an error, so call result is the same error
        terr = terr.meet(actual);
        actual = formal;   // Lift actual to worse-case valid argument type
      }
      //if( !actual.isa(formal) ) // Actual is not a formal; accumulate type errors
      //  terr = terr.meet(TypeErr.make(_badargs.errMsg("%s is not a %s"), actual, formal, false));
    }
    return terr.meet(tval);  // Return any errors, or the Epilog return type
  }
  Type nargerr(TypeFun tfun) {
    return TypeErr.make(_badargs.errMsg("Passing "+nargs()+" arguments to "+tfun+" which takes "+tfun.nargs()+" arguments"));
  }
  
  // Called from the data proj.  Return a TypeNode with proper casting on return result.
  TypeNode upcast_return(GVNGCM gvn) {
    Type t = _cast_ret;
    if( t==null ) return null;  // No cast-in-progress
    _cast_ret = null;           // Gonna upcast the return result now
    gvn.add_work(this);         // Revisit after the data-proj cleans out
    return new TypeNode(t,null,_cast_P);
  }

  @Override public Type all_type() { return TypeTuple.make_all(Type.CTRL,TypeErr.ALL); }
  @Override public int hashCode() { return super.hashCode()+_rpc; }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof CallNode) ) return false;
    CallNode call = (CallNode)o;
    return _rpc==call._rpc;
  }

}

