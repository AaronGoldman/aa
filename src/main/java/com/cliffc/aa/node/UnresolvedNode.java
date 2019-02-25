package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Ary;

public class UnresolvedNode extends Node {
  UnresolvedNode( Node... funs ) { super(OP_UNR,funs); }
  @Override String xstr() {
    if( in(0) instanceof EpilogNode ) {
      EpilogNode epi = (EpilogNode)in(0);
      if( epi.in(3) instanceof FunNode )
        return "Unr:"+epi.fun().name();
    }
    return "Unr???";
  }
  @Override public Node ideal(GVNGCM gvn) {
    if( _defs._len < 2 ) throw AA.unimpl(); // Should collapse
    return null;
  }
  @Override public Type value(GVNGCM gvn) {
    Type t = Type.ALL;
    for( Node def : _defs )
      t = t.join(gvn.type(def)); // Join of incoming functions
    return t;
  }
  // Filter out all the wrong-arg-count functions
  public Node filter( GVNGCM gvn, int nargs ) {
    Node x = null;
    for( Node epi : _defs ) {
      TypeFunPtr tf = ((EpilogNode)epi).fun()._tf;
      if( tf.nargs() != nargs ) continue;
      if( x == null ) x = epi;
      else if( x instanceof UnresolvedNode ) x.add_def(epi);
      else x = new UnresolvedNode(x,epi);
    }
    return x instanceof UnresolvedNode ? gvn.xform(x) : x;
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
  Node resolve( GVNGCM gvn, CallNode call ) {
    // Set of possible choices with fewest conversions
    Ary<Node> ns = new Ary<>(new Node[1],0);
    int min_cvts = 999;         // Required conversions
    int[] cvts = new int[_defs._len];

    // For each function, see if the actual args isa the formal args.  If not,
    // toss it out.  Also count conversions, and keep the minimal conversion
    // function with all arguments known.
    outerloop:
    for( Node epi : _defs ) {
      TypeFun tepi = (TypeFun)gvn.type(epi);
      TypeFunPtr fun = tepi.fun();
      if( fun.nargs() != call.nargs() ) continue; // Wrong arg count, toss out
      TypeTuple formals = fun._ts;   // Type of each argument
      // Now check if the arguments are compatible in all, keeping lowest cost
      int xcvts = 0;             // Count of conversions required
      boolean unk = false;       // Unknown arg might be incompatible or free to convert
      for( int j=0; j<call.nargs(); j++ ) {
        Type actual = gvn.type(call.arg(j));
        if( actual==Type.XSCALAR && call.arg(j) instanceof ConNode )
          continue; // Forced super-high arg is always compatible before formal is dead
        Type tx = actual.join(formals.at(j));
        if( tx.above_center() ) // Actual and formal have values in common?
          continue outerloop;   // No, this function will never work; e.g. cannot cast 1.2 as any integer
        byte cvt = actual.isBitShape(formals.at(j)); // +1 needs convert, 0 no-cost convert, -1 unknown, 99 never
        if( cvt == 99 )         // Happens if actual is e.g. TypeErr
          continue outerloop;   // No, this function will never work
        if( cvt == -1 ) unk = true; // Unknown yet
        else xcvts += cvt;          // Count conversions
      }
      if( !unk && xcvts < min_cvts ) min_cvts = xcvts; // Keep minimal known conversion
      cvts[ns._len] = xcvts;    // Keep count of conversions
      ns.add(epi);              // This is an acceptable choice, so far (args can be made to work)
    }
    // Toss out choices with strictly more conversions than the minimal
    for( int i=0; i<ns._len; i++ )
      if( cvts[i] > min_cvts ) {
        cvts[i] = cvts[ns._len-1];
        ns.del(i--);
      }

    if( ns._len==0 ) return null;          // No choices apply?  No changes.
    if( ns._len==1 ) return ns.at(0);      // Return the one choice
    if( ns._len==_defs._len ) return null; // No improvement
    return gvn.xform(new UnresolvedNode(ns.asAry())); // return shrunk choice list
  }
  
  @Override public Type all_type() { return TypeFun.GENERIC_FUN; }
  
  // Return the op_prec of the returned value.  Not sensible except when called
  // on primitives.  Should be the same across all defs.
  @Override public byte op_prec() {
    byte prec = _defs.at(0).op_prec();
    assert _defs._len < 2 || _defs.at(1).op_prec() == prec;
    return prec;
  }
  // Return the op_prec of the returned value.  Not sensible except when called
  // on primitives.  Should be the same across all defs.
  @Override public byte may_prec() {
    byte prec = -1;
    for( Node f : _defs ) if( (prec=f.op_prec()) >= 0 ) return prec;
    return prec;
  }
}
