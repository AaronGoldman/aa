package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.*;

import java.util.BitSet;

// Merge results; extended by ParmNode
public class PhiNode extends Node {
  final Parse _badgc;
  final Type _t;
  private PhiNode( byte op, Type t, Parse badgc, Node... vals ) {
    super(op,vals);
    _badgc = badgc;
    _t = t;
    // Recompute, since _t was null inside of Node.Node calling basic_liveness.
    _live = basic_liveness() ? TypeMem.EMPTY : TypeMem.FULL;
  }
  public PhiNode( Type t, Parse badgc, Node... vals ) { this(OP_PHI,t,badgc,vals); }
  // For ParmNodes
  PhiNode( byte op, Node fun, Type tdef, Node defalt, Parse badgc ) { this(op,tdef,badgc, fun,defalt); }
  @Override public int hashCode() { return super.hashCode()+_t.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    if( !(o instanceof PhiNode) ) return false;
    PhiNode phi = (PhiNode)o;
    return _t==phi._t;
  }

  @Override public Node ideal(GVNGCM gvn, int level) {
    if( gvn.type(in(0)) == Type.XCTRL ) return null;
    RegionNode r = (RegionNode) in(0);
    assert r._defs._len==_defs._len;
    if( gvn.type(r) == Type.XCTRL ) return null; // All dead, c-prop will fold up
    if( r._defs.len() > 1 &&  r.in(1) == Env.ALL_CTRL ) return null;
    if( r instanceof FunNode && ((FunNode)r).noinline() )
      return null; // Do not start peeling apart parameters to a no-inline function
    // If only 1 unique live input, return that
    Node live=null;
    for( int i=1; i<_defs._len; i++ ) {
      if( gvn.type(r.in(i))==Type.XCTRL ) continue; // Ignore dead path
      Node n = in(i);
      if( n==this || n==live ) continue; // Ignore self or duplicates
      if( live==null ) live = n;         // Found unique live input
      else {
        Type tn = gvn.type(n);
        Type tl = gvn.type(live);
        if( live   instanceof ConNode && tl.above_center() && tl.isa(tn) ) live = n; // Keep non-constant
        else if( n instanceof ConNode && tn.above_center() && tn.isa(tl) ) ; // keep live, the constant does not add anything
        else live=this;         // Found 2nd live input, no collapse
      }
    }
    if( live != this ) return live; // Single unique input

    // Phi of a MemMerge becomes a MemMerge of a Phi - increasing the precision
    // of memory edges.
    if( gvn.type(in(1)) instanceof TypeMem ) {
      Node rez = expand_memory(r,gvn);
      if( rez != null ) return rez;
    }

    return null;
  }

  // Phi of a MemMerge becomes a MemMerge of a Phi - increasing the precision
  // of memory edges.
  private Node expand_memory(RegionNode r, GVNGCM gvn) {
    // Profit heuristic: all MemMerges are stable after xform and are available
    // on all paths and the Phi is the sole user, so the input MemMerges will
    // die after this xform.  This is an expanding xform: try not to expand
    // dead nodes.
    for( int i=1; i<_defs._len; i++ ) {
      Node mem = in(i);
      if( mem._uses._len!=1 || !(mem instanceof MemMergeNode) )
        return null;            // Not a self-single-user MemMerge
    }
    // Find all aliases
    BitSet bs = new BitSet();
    bs.set(BitsAlias.ALL);
    for( int i=1; i<_defs._len; i++ )
      if( in(i) instanceof MemMergeNode ) {
        MemMergeNode xmem = (MemMergeNode)in(i);
        for( int j=0; j<xmem._defs._len; j++ )
          bs.set(xmem.alias_at(j));
      }

    // Make sure each alias merged is skinny
    for( int alias = bs.nextSetBit(0); alias >= 0; alias = bs.nextSetBit(alias+1) )
      if( alias > 1 )
        for( int i=1; i<_defs._len; i++ ) {
          MemMergeNode mmem = (MemMergeNode)in(i);
          Node nalias = mmem.alias2node(alias);
          Type tnalias = gvn.type(nalias);
          if( !(tnalias instanceof TypeObj) )
            return null;        // MemMerge is being used to filter fat memory
          if( !(gvn.type(((MemMergeNode)in(i)).alias2node(alias)) instanceof TypeObj) )
            return null;
        }

    // Do the expansion.  Fill in all the phis, one by one, and plug into the
    // giant MemMerge at the end.
    MemMergeNode mmem = new MemMergeNode(null);
    for( int alias = bs.nextSetBit(0); alias >= 0; alias = bs.nextSetBit(alias+1) ) {
      PhiNode phi = new PhiNode(TypeStruct.ALLSTRUCT,_badgc,r);
      for( int i=1; i<_defs._len; i++ ) {
        // Take the matching narrow slice for the alias, except for alias ALL
        // which can keep taking undistinguished memory.  The resulting memory
        // is {ALL-aliases} but currently only represented as ALL.
        Node n = ((MemMergeNode)in(i)).alias2node(alias);
        if( alias==BitsAlias.ALL ) {
          assert gvn.type(n) instanceof TypeMem;
        } else {
          if( gvn.type(n) == TypeObj.XOBJ && !(n instanceof ConNode) )
            n = gvn.con(TypeObj.XOBJ);
          assert gvn.type(n) instanceof TypeObj;
        }
        phi.add_def(n);
      }
      Node obj = gvn.xform(phi);
      if( alias==BitsAlias.ALL ) mmem.set_def(0,obj,gvn);
      else       mmem.create_alias_active(alias,obj,gvn);
    }
    return mmem;
  }


  @Override public Type value(GVNGCM gvn) {
    Type ctl = gvn.type(in(0));
    Type t = all_type().dual();
    if( ctl != Type.CTRL ) return ctl.above_center() ? t : t.dual();
    RegionNode r = (RegionNode) in(0);
    assert r._defs._len==_defs._len;
    for( int i=1; i<_defs._len; i++ )
      if( gvn.type(r.in(i))==Type.CTRL ) // Only meet alive paths
        t = t.meet(gvn.type(in(i)));
    return t.bound(_t);                  // Limit to sane results
  }
  // Only interested in memory aliases, if merging memory
  @Override public boolean basic_liveness() {  return !(_t instanceof TypeMem); }

  @Override public Type all_type() { return _t; }
  @Override public String err(GVNGCM gvn) {
    if( !(in(0) instanceof FunNode && ((FunNode)in(0))._name.equals("!") ) && // Specifically "!" takes a Scalar
        (gvn.type(this).contains(Type.SCALAR) ||
         gvn.type(this).contains(Type.NSCALR)) ) // Cannot have code that deals with unknown-GC-state
      return _badgc.errMsg("Cannot mix GC and non-GC types");
    return null;
  }
}
