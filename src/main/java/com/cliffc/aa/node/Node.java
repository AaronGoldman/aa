package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.SB;

/** an implementation of language AA
 */

// Sea-of-Nodes
public abstract class Node {
  static private int CNT=0;
  public final int _uid=CNT++; // Unique ID, will have gaps, used to give a dense numbering to nodes

  // Defs.  Generally fixed length, ordered, nulls allowed, no unused trailing space
  public Ary<Node> _defs;
  
  // Uses.  Generally variable length; unordered, no nulls, compressed, unused trailing space
  public Ary<Node> _uses = new Ary<>(new Node[1],0);

  Node() { this(new Node[0]); }
  Node( Node... defs ) {
    _defs = new Ary<>(defs);
    for( Node def : defs ) def.add_use(this);
  }

  
  // Short string name
  abstract String str();
  private SB xstr(SB sb) { return sb.p(_uid).p("=").p(str()); }
  @Override public String toString() {
    SB sb = xstr(new SB()).p("(");
    boolean first=true;
    for( Node n : _defs ) { n.xstr(sb.p(first?"":",")); first=false; }
    return sb.p(")").toString();
  }
  
  // graph rewriting
  abstract public Node ideal(GVNGCP gvn);

  // Compute the current best Type for this Node, based on the types of its inputs
  abstract public Type value(GVNGCP gvn);

  // Worse-case type for this Node
  public Type all_type() { return Type.ALL; }
  // Improve worse-case type, for those 
  public void lift_type(Type t) { }
  
  // Operator precedence is only valid for ConNode of binary functions
  public int op_prec() { return -1; }

  Type[] types( GVNGCP gvn ) {
    Type[] ts = new Type[_defs._len];
    for( int i=0; i<_defs._len; i++ )
      ts[i] = gvn.type(_defs.at(i));
    return ts;
  }
  
  public Node add_def(Node n) { _defs.add(n); return this; }
  // Strictly add uses (no defs)
  public void add_use(Node n) { _uses.add(n); }
}

class RootNode extends Node {
  @Override String str() { return "root"; }
  @Override public Node ideal(GVNGCP gvn) { return null; }
  @Override public Type value(GVNGCP gvn) { throw AA.unimpl(); }
}
