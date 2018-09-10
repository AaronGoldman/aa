package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.SB;
import com.cliffc.aa.type.Type;
import com.cliffc.aa.type.TypeErr;

import java.util.BitSet;
import java.util.function.Predicate;

// Sea-of-Nodes
public abstract class Node implements Cloneable {
  static final byte OP_CALL  = 1;
  static final byte OP_CAST  = 2;
  static final byte OP_CON   = 3;
  static final byte OP_EPI   = 4;
  static final byte OP_ERR   = 5;
  static final byte OP_FUN   = 6;
  static final byte OP_IF    = 7;
  static final byte OP_LOAD  = 8;
  static final byte OP_NEW   = 9;
  static final byte OP_PARM  =10;
  static final byte OP_PHI   =11;
  static final byte OP_PRIM  =12;
  static final byte OP_PROJ  =13;
  static final byte OP_REGION=14;
  static final byte OP_RPC   =15;
  static final byte OP_SCOPE =16;
  static final byte OP_TMP   =17;
  static final byte OP_TYPE  =18;
  static final byte OP_UNR   =19;
  static final byte OP_MAX   =20;
  private static final String[] STRS = new String[] { null, "Call", "Cast", "Con", "Epi", "Err", "Fun", "If", "Load", "New", "Parm", "Phi", "Prim", "Proj", "Region", "RPC", "Scope", "Tmp", "Type", "Unresolved" };

  public int _uid=Env._gvn.uid(); // Unique ID, will have gaps, used to give a dense numbering to nodes
  final byte _op;

  // Defs.  Generally fixed length, ordered, nulls allowed, no unused trailing space.  Zero is Control.
  public Ary<Node> _defs;
  // Add def/use edge
  public Node add_def(Node n) { _defs.add(n); if( n!=null ) n._uses.add(this); return n; }
  public Node in( int i) { return _defs.at(i); }
  // Replace def/use edge
  public Node set_def( int idx, Node n, GVNGCM gvn ) {
    Node old = _defs.at(idx);  // Get old value
    // Add edge to new guy before deleting old, in case old goes dead and
    // recursively makes new guy go dead also
    if( (_defs._es[idx] = n) != null ) n._uses.add(this);
    return unuse(old, gvn);
  }
  private Node unuse( Node old, GVNGCM gvn ) {
    if( old != null ) {
      old._uses.del(old._uses.find(this));
      if( old._uses._len==0 && !(old instanceof ScopeNode) ) gvn.kill(old); // Recursively begin deleting
    }
    return this;
  }
  // Return Node at idx, withOUT auto-deleting it, even if this is the last
  // use.  Used by the parser to retrieve final Nodes from tmp holders.  Does
  // NOT preserve order.
  public Node del( int idx ) {
    Node n = _defs.del(idx);
    if( n != null ) n._uses.del(n._uses.find(this));
    return n;
  }
  public Node pop( ) { return del(_defs._len-1); }
  // Remove Node at idx, auto-delete and preserve order.
  public void remove(int idx, GVNGCM gvn) { unuse(_defs.remove(idx),gvn); }

  // Uses.  Generally variable length; unordered, no nulls, compressed, unused trailing space
  public Ary<Node> _uses = new Ary<>(new Node[1],0);

  Node( byte op ) { _op = op; _defs = new Ary<>(new Node[1],0); }
  Node( byte op, Node... defs ) {
    _op = op;
    _defs = new Ary<>(defs);
    for( Node def : defs ) if( def != null ) def._uses.add(this);
  }

  // Make a copy of the base node, with no defs nor uses and a new UID.
  <N extends Node> N copy() {
    try {
      N n = (N)clone();                   // Preserve base java type
      n._uid = Env._gvn.uid();            // A new UID
      n._defs = new Ary<>(new Node[1],0); // New empty defs
      n._uses = new Ary<>(new Node[1],0); // New empty uses
      return n;
    } catch( CloneNotSupportedException cns ) {
      throw new RuntimeException(cns);
    }
  }
  
  // Short string name
  String xstr() { return STRS[_op]; } // Self   short name
  String  str() { return xstr(); }    // Inline short name
  @Override public String toString() { return dump(0,new SB(),null).toString(); }
  public String dump( int max ) { return dump(max,null); }
  public String dump( int max, GVNGCM gvn ) { return dump(0, new SB(),max,new BitSet(),gvn).toString();  }
  private SB dump( int d, SB sb, GVNGCM gvn ) {
    sb.i(d).p(_uid).p(':').p(xstr()).p(' ');
    for( Node n : _defs ) (n == null ? sb.p('_') : n.str(sb)).p(' ');
    sb.p(" [[");
    for( Node n : _uses ) sb.p(n._uid).p(' ');
    sb.p("]]  ");
    if( gvn != null ) sb.p(gvn.type(this).toString());
    return sb;
  }
  private SB str(SB sb) { return sb.p(_uid).p(':').p(str()).p(' '); }
  // Recursively print, up to depth
  private SB dump( int d, SB sb, int max, BitSet bs, GVNGCM gvn ) {
    if( bs.get(_uid) ) return sb;
    bs.set(_uid);
    if( d < max ) {    // Limit at depth
      // Print parser scopes first (deepest)
      for( Node n : _defs ) if( n instanceof ScopeNode && n._uid != 0 ) n.dump(d+1,sb,max,bs,gvn);
      // Print constants early
      for( Node n : _defs ) if( n instanceof ConNode ) n.dump(d+1,sb,max,bs,gvn);
      // Do not recursively print root Scope, nor Unresolved of primitives.
      // These are too common, and uninteresting.
      for( Node n : _defs ) if( n != null && n._uid < GVNGCM._INIT0_CNT ) bs.set(n._uid);
      // Recursively print most of the rest, just not the multi-node combos
      for( Node n : _defs ) if( n != null && !n.is_multi_head() && !n.is_multi_tail() ) n.dump(d+1,sb,max,bs,gvn);
      // Print anything not yet printed, including multi-node combos
      for( Node n : _defs ) if( n != null ) n.dump(d+1,sb,max,bs,gvn);
    }
    // Print multi-node combos all-at-once
    Node x = is_multi_tail() ? in(0) : this;
    if( x.is_multi_head() ) {
      int dx = d+(x==this?0:1);
      for( Node n : x._uses ) if( n.is_multi_tail() )
        for( Node m : n._defs ) m.dump(dx,sb,max,bs,gvn);
      bs.clear(_uid);           // Reset for self, so prints in the mix
      x.dump(dx,sb,bs,gvn); // Conditionally print head of combo
      // Print all combo tails, if not already printed
      for( Node n : x._uses ) if( n.is_multi_tail() ) n.dump(dx-1,sb,bs,gvn);
      return sb;
    } else { // Neither combo head nor tail, just print
      return dump(d,sb,gvn).nl();
    }
  }
  private SB dump(int d, SB sb, BitSet bs, GVNGCM gvn) {
    if( bs.get(_uid) ) return sb;
    bs.set(_uid);
    return dump(d,sb,gvn).nl();
  }
  private boolean is_multi_head() { return _op==OP_FUN  || _op==OP_REGION || _op==OP_CALL || _op==OP_IF; }
  private boolean is_multi_tail() { return _op==OP_PARM || _op==OP_PHI    || _op==OP_PROJ              ; }
  
  public  Node find( int uid ) { return find(uid,new BitSet()); }
  private Node find( int uid, BitSet bs ) {
    if( _uid==uid ) return this;
    if( bs.get(_uid) ) return null;
    bs.set(_uid);
    Node m;
    for( Node n : _defs ) if( n!=null && (m=n.find(uid,bs)) !=null ) return m;
    return null;
  }

  // Graph rewriting.  Can change defs, including making new nodes - but if it
  // does so, all new nodes will first call gvn.xform().  If gvn._opt if false,
  // not allowed to remove CFG edges (loop backedges and function-call entry
  // points have not all appeared).  Returns null if no-progress, or a better
  // version of 'this'.
  abstract public Node ideal(GVNGCM gvn);

  // Compute the current best Type for this Node, based on the types of its inputs.
  // May return the local "all_type()", especially if its inputs are in error.
  abstract public Type value(GVNGCM gvn);

  // Return any type error message, or null if no error
  public String err(GVNGCM gvn) { return null; }
  
  // Worse-case type for this Node
  public Type all_type() { return TypeErr.ALL; }
  
  // Operator precedence is only valid for ConNode of binary functions
  public byte  op_prec() { return -1; }
  public byte may_prec() { return -1; }

  // Hash is function+inputs, or opcode+input_uids, and is invariant over edge
  // order (so we can swap edges without rehashing)
  @Override public int hashCode() {
    int sum = _op;
    for( int i=0; i<_defs._len; i++ ) if( _defs._es[i] != null ) sum ^= _defs._es[i]._uid;
    return sum;
  }
  // Equals is function+inputs, or opcode+input_uids.  Uses pointer-equality
  // checks for input equality checks.
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !(o instanceof Node) ) return false;
    Node n = (Node)o;
    if( _op != n._op ) return false;
    if( _defs._len != n._defs._len ) return false;
    // Note pointer-equality
    for( int i=0; i<_defs._len; i++ ) if( _defs._es[i] != n._defs._es[i] ) return false;
    return true;
  }

  // Used in Parser just after an if-test to sharpen the tested variables.
  // This is a mild optimization, since e.g. follow-on Loads which require a
  // non-null check will hash to the pre-test Load, and so bypass this
  // sharpening.
  public Node sharpen( GVNGCM gvn, ScopeNode scope, TmpNode tmp ) { return this; }
    
  // Gather errors; backwards reachable control uses only
  public Ary<String> walkerr_use( Ary<String> errs, BitSet bs, GVNGCM gvn ) {
    assert !is_dead();
    if( bs.get(_uid) ) return errs; // Been there, done that
    bs.set(_uid);                   // Only walk once
    if( gvn.type(this) != Type.CTRL )
      return errs;                // Ignore non-control
    if( this instanceof ErrNode ) // Gather errors
      errs = Parse.add_err(errs,((ErrNode)this)._msg);
    for( Node use : _uses )     // Walk control users for more errors
      errs = use.walkerr_use(errs,bs,gvn);
    return errs;
  }
  
  // Gather errors; forwards reachable data uses only
  public Ary<String> walkerr_def( Ary<String> errs, BitSet bs, GVNGCM gvn ) {
    assert !is_dead();
    if( bs.get(_uid) ) return errs; // Been there, done that
    bs.set(_uid);                   // Only walk once
    String msg = err(gvn);          // Get any error
    if( msg != null )  errs = Parse.add_err(errs,msg); // Gather errors
    
    for( int i=0; i<_defs._len; i++ ) {
      Node def = _defs.at(i);   // Walk data defs for more errors
      if( def == null ) continue;
      // All dead paths been cleaned out
      assert !(this instanceof RegionNode) || gvn.type(def)== Type.CTRL;
      errs = def.walkerr_def(errs,bs,gvn);
    }
    return errs;
  }
  
  // Gather errors; forwards reachable data uses only
  public Ary<String> walkerr_gc( Ary<String> errs, BitSet bs, GVNGCM gvn ) {
    if( bs.get(_uid) ) return errs;// Been there, done that
    bs.set(_uid);                  // Only walk once
    if( this instanceof PhiNode &&
        Type.SCALAR.isa(gvn.type(this)) ) // Cannot have code that deals with unknown-GC-state
      errs = Parse.add_err(errs,((PhiNode)this)._badgc);
    for( int i=0; i<_defs._len; i++ )
      if( in(i) != null ) in(i).walkerr_gc(errs,bs,gvn);
    return errs;
  }
  public boolean is_dead() { return _uses == null; }
  public void set_dead( ) { _defs = _uses = null; }   // TODO: Poor-mans indication of a dead node, probably needs to recycle these...

  // Overridden in subclasses that return TypeTuple value types.  Such nodes
  // are always followed by ProjNodes to break out the tuple slices.  If the
  // node optimizes, each ProjNode becomes a copy of some other value... based
  // on the ProjNode index
  public Node is_copy(GVNGCM gvn, int idx) { return null; }

  // Only true for some EpilogNodes
  public boolean is_forward_ref() { return false; }
  
  // Walk a subset of the dominator tree, looking for the last place (highest
  // in tree) this predicate passes, or null if it never does.  
  Node walk_dom_last(Predicate<Node> P) {
    assert in(0) != null;       // All default control nodes pass ctrl in slot 0
    Node n = in(0).walk_dom_last(P);
    if( n != null ) return n;   // Take last answer first
    return P.test(this) ? this : null;
  }
  
}
