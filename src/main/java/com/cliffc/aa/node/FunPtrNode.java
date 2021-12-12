package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.tvar.TV2;
import com.cliffc.aa.type.*;

import static com.cliffc.aa.AA.*;

// See CallNode and FunNode comments. The FunPtrNode converts a RetNode into a
// TypeFunPtr with a constant fidx and variable displays.  Used to allow first
// class functions to be passed about.

// FIDXs above-center are used by UnresolvedNode to represent choice.
// Normal FunPtrs, in both GCP and Opto/Iter, should be a single (low) FIDX.

// Display is e.g. *[12] (alias 12 struct), or some other thing to represent an
// unused/dead display.  I've been using either ANY or XNIL.

// There are several invariants we'd like to have:

// The FIDX and DISP match sign: so {-15,ANY} and {+15,NIL} are OK, but
// {+15,XNIL} and {+15,ANY} are not.  This one is in conflict with the others,
// and is DROPPED.  Instead we allow e.g. {+15,ANY} to indicate a FIDX 15 with
// no display.
//
// FunPtrNodes strictly fall during GCP; lift during Opto.
// So e.g. any -> [-15,any] -> [-15,-12] -> [+15,+12] -> [+15,all] -> all.
// But need to fall preserving the existing of DISP.
// So e.g.  any -> [-15,any] -> [-15,xnil] -> [+15,nil] -> [+15,all] -> all.
// So e.g.  any -> [-15,-12] ->                            [+15,+12] -> all.
//
// FunPtrNodes start being passed e.g. [+12], but during GCP can discover DISP
// is dead... but then after GCP need to migrate the types from [+15,+12] to
// [+15,nil] which is sideways.  Has to happen in a single monolithic pass
// covering all instances of [+15,+12].  Also may impact mixed +15 and other
// FIDXs with unrelated DISPs.  Instead a dead display just flips to ANY.

public final class FunPtrNode extends Node {
  public String _name;          // Optional for debug only

  // Every var use that results in a function, so actually only these FunPtrs,
  // needs to make a "fresh" copy before unification.  "Fresh" makes a
  // structural copy of the TVar, keeping TVars from Nodes currently in-scope
  // as-is, and making structural copies of out-of-scope TVars.  The only
  // interesting thing is when an out-of-scope TVar uses the same TVar
  // internally in different parts - the copy replicates this structure.  When
  // unified, it forces equivalence in the same places.
  public  FunPtrNode( String name, RetNode ret, Node display ) {
    super(OP_FUNPTR,ret,display);
    _name = name;
  }
  // Explicitly, no display
  public  FunPtrNode( String name, RetNode ret ) { this(name,ret, Env.ANY ); }
  // Display (already fresh-loaded) but no name.
  public  FunPtrNode( RetNode ret, Node display ) { this(null,ret,display); }
  public RetNode ret() { return in(0)==null ? null : (RetNode)in(0); }
  public Node display(){ return in(1); }
  public FunNode fun() { return ret().fun(); }
  public FunNode xfun() { RetNode ret = ret(); return ret !=null && ret.in(4) instanceof FunNode ? ret.fun() : null; }
  public int nargs() { return ret()._nargs; }
  //@Override public FunPtrNode funptr() { return this; }
  //@Override public UnresolvedNode unk() { return null; }
  // Self short name
  @Override public String xstr() {
    if( is_dead() || _defs._len==0 ) return "*fun";
    RetNode ret = ret();
    return ret==null ? "*fun" : "*"+_name;
  }
  // Inline longer name
  @Override String str() {
    if( is_dead() ) return "DEAD";
    if( _defs._len==0 ) return "MAKING";
    RetNode ret = ret();
    if( ret==null || ret.is_copy() ) return "gensym:"+xstr();
    FunNode fun = ret.fun();
    return fun==null ? xstr() : fun.str();
  }

  // Debug only: make an attempt to bind name to a function
  public void bind( String tok ) {
    _name = tok;
    fun().bind(tok);
  }

  @Override public Node ideal_reduce() {

    Node dsp = display();
    if( dsp!=Env.ANY ) {
      Type tdsp = dsp._val;
      FunNode fun;
      // Display is known dead?
      if( (tdsp instanceof TypeMemPtr && ((TypeMemPtr)tdsp)._obj==TypeObj.UNUSED) ||
          // Collapsing to a gensym, no need for display
          ret().is_copy() ||
          // Also unused if function has no display parm.
          ((fun=xfun())!=null && fun.is_copy(0)==null && fun.parm(DSP_IDX)==null)  )
        //  return set_def(1,Env.ANY); // No display needed
        throw unimpl();
    }
    return null;
  }
  // Called if Display goes unused
  @Override public void add_flow_use_extra(Node chg) {
    Type tdsp = display()._val;
    if( tdsp instanceof TypeMemPtr && ((TypeMemPtr)tdsp)._obj==TypeObj.UNUSED )
      Env.GVN.add_reduce(this);
  }

  // Is the display used?  Calls may use the TFP portion but not the display.
  private boolean display_used() {
    for( Node call : _uses ) {
      if( !(call instanceof CallNode) ) return true; // Anything other than a Call is using the display
      for( int i=ARG_IDX; i<call.len(); i++ )
        if( call.in(i)==this ) return true; // Call-use other than the last position is using the display portion of this FPTR
      assert ((CallNode)call).fdx()==this;
      if( ProjNode.proj(call,DSP_IDX)!=null )
        return true;            // Call needs the display and fptr both
    }
    return false;               // Only uses are Calls needing the fptr but not the display
  }


  @Override public Type value() {
    if( !(in(0) instanceof RetNode) )
      return TypeFunPtr.EMPTY;
    RetNode ret = ret();
    TypeTuple tret = (TypeTuple)(ret._val instanceof TypeTuple ? ret._val : ret._val.oob(TypeTuple.RET));
    return TypeFunPtr.make(ret._fidx,nargs(),display()._val,tret.at(REZ_IDX));
  }
  @Override public void add_flow_extra(Type old) {
    if( old==_live )            // live impacts value
      Env.GVN.add_flow(this);
    if( old instanceof TypeFunPtr )
      for( Node use : _uses )
        if( use instanceof UnresolvedNode )
          for( Node call : use._uses )
            if( call instanceof CallNode ) {
              TypeFunPtr tfp = CallNode.ttfp(call._val);
              if( tfp.fidxs()==((TypeFunPtr)old).fidxs() )
                Env.GVN.add_flow(call);
            }
  }

  @Override public TypeMem live_use(Node def ) {
    return def==in(0)
      ? TypeMem.ALLMEM          // Returns are complex-alive
      : (_live==TypeMem.LNO_DISP ? TypeMem.DEAD : TypeMem.ALIVE); // Display is alive or dead
  }

  @Override public boolean unify( boolean test ) {
    TV2 self = tvar();
    if( self.is_err() ) return false;
    RetNode ret = ret();
    if( ret.is_copy() ) return false; // GENSYM
    FunNode fun = ret.fun();

    boolean progress = false;
    if( !self.is_fun() ) {      // Force a function if not already
      if( test ) return true;
      progress = self.unify(TV2.make_fun(ret.rez(),((TypeFunPtr)_val).make_no_disp(),"FunPtr_unify"),test);
      self = self.find();
    }

    // Return
    progress |= self.unify_at(ret.rez()," ret",ret.rez().tvar(),test);

    // Each normal argument from the parms directly
    Node[] parms = fun.parms();
    for( int i=DSP_IDX; i<parms.length; i++ ) {
      if( parms[i]==null ) continue;
      String key = (""+i).intern();
      TV2 old = self.get(key);
      TV2 arg = parms[i].tvar();
      assert arg!=null;//if( arg==null )  arg = TV2.make_leaf(fun,"FunPtr_unify"); // null on 1st visit to a missing (unused) parm
      if( old==arg ) continue;      // No progress
      if( test ) return true; // Early cutout
      progress |= self.unify_at(parms[i],key,arg,test);
      // The display is part of the fat function-pointer.  Here we act like an
      // HM.Apply or a CallEpi.unify.
      if( i==DSP_IDX && display()!=Env.ANY ) {
        TV2 tdsp = display().tvar();
        progress |= tdsp.unify(arg,test);
      }
    }
    return progress;
  }

  // Return the op_prec of the returned value.  Not sensible except when called
  // on primitives.
  @Override public byte op_prec() { return fun()._op_prec; }

  // Instead of returning the pre-call memory on true, returns self.
  // Changes as the graph changes, because works purely off of graph shape.
  @Override Node is_pure_call() {
    // See if the RetNode points to a Parm:mem (so no mods on memory).
    RetNode ret = ret();
    if( ret.is_copy() ) return null;
    FunNode fun = ret.fun();
    if( fun.noinline() ) return null; // Disallow if no-inline
    Node mem = ret.mem();
    if( mem.in(0)==fun && mem instanceof ParmNode ) return this; // Parm:mem on fun, no mods to memory
    return null;
  }

}
