package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.SB;
import com.cliffc.aa.type.Type;

import java.util.BitSet;
import java.util.function.Predicate;

// Sea-of-Nodes
public abstract class Node implements Cloneable {
  static final byte OP_CALL   = 1;
  static final byte OP_CALLEPI= 2;
  static final byte OP_CAST   = 3;
  static final byte OP_CON    = 4;
  static final byte OP_EPI    = 5;
  static final byte OP_ERR    = 6;
  static final byte OP_FUN    = 7;
  static final byte OP_FUNPTR = 8;
  static final byte OP_IF     = 9;
  static final byte OP_LIBCALL=10;
  static final byte OP_LOAD   =11;
  static final byte OP_MERGE  =12;
  static final byte OP_NEW    =13;
  static final byte OP_PARM   =14;
  static final byte OP_PHI    =15;
  static final byte OP_PRIM   =16;
  static final byte OP_PROJ   =17;
  static final byte OP_REGION =18;
  static final byte OP_RET    =19;
  static final byte OP_SCOPE  =20;
  static final byte OP_START  =21;
  static final byte OP_STORE  =22;
  static final byte OP_TMP    =23;
  static final byte OP_TYPE   =24;
  static final byte OP_UNR    =25;
  static final byte OP_MAX    =26;
  
  private static final String[] STRS = new String[] { null, "Call", "CallEpi", "Cast", "Con", "Epi", "Err", "Fun", "FunPtr", "If", "LibCall", "Load", "Merge", "New", "Parm", "Phi", "Prim", "Proj", "Region", "Return", "Scope", "Start", "Store", "Tmp", "Type", "Unresolved" };

  public int _uid;  // Unique ID, will have gaps, used to give a dense numbering to nodes
  final byte _op;   // Opcode (besides the object class), used to avoid v-calls in some places
  public byte _keep;// Keep-alive in parser, even as last use goes away

  // Defs.  Generally fixed length, ordered, nulls allowed, no unused trailing space.  Zero is Control.
  public Ary<Node> _defs;
  // Add def/use edge
  public Node add_def(Node n) { _defs.add(n); if( n!=null )
    n._uses.add(this); return n; }
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
      if( old._uses._len==0 && old._keep==0 ) gvn.kill(old); // Recursively begin deleting
      if( (!old.is_dead() && old.is_multi_head() && is_multi_tail()) ||
          old.ideal_impacted_by_losing_uses() )
        gvn.add_work(old);
    }
    return this;
  }
  public Node   keep(          ) { _keep++;  return this; }
  public Node unhook(          ) { _keep--;  return this; }
  public void unkeep(GVNGCM gvn) { _keep--;  if( _keep==0 && _uses._len==0 ) gvn.kill(this); }
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
  public Ary<Node> _uses;

  Node( byte op ) { this(op,new Node[0]); }
  Node( byte op, Node... defs ) {
    _op   = op;
    _uid  = GVNGCM.uid();
    _defs = new Ary<>(defs);
    _uses = new Ary<>(new Node[1],0);
    for( Node def : defs ) if( def != null ) def._uses.add(this);
   }

  // Make a copy of the base node, with no defs nor uses and a new UID.
  Node copy(GVNGCM gvn) {
    try {
      Node n = (Node)clone();
      n._uid = GVNGCM.uid();              // A new UID
      n._defs = new Ary<>(new Node[1],0); // New empty defs
      n._uses = new Ary<>(new Node[1],0); // New empty uses
      return n;
    } catch( CloneNotSupportedException cns ) { throw new RuntimeException(cns); }

  }
  
  // Short string name
  String xstr() { return STRS[_op]; } // Self   short  name
  String  str() { return xstr(); }    // Inline longer name
  @Override public String toString() { return dump(0,new SB(),null).toString(); }
  public String dump( int max ) { return dump(max,null); }
  public String dump( int max, GVNGCM gvn ) { return dump(max,gvn,_uid<GVNGCM._INIT0_CNT);  }
  public String dump( int max, GVNGCM gvn, boolean prims ) { return dump(0, new SB(),max,new BitSet(),gvn,prims).toString();  }
  private SB dump( int d, SB sb, GVNGCM gvn ) {
    String xs = String.format("%4d: %-7.7s ",_uid,xstr());
    sb.i(d).p(xs);
    if( is_dead() ) return sb.p("DEAD");
    for( Node n : _defs ) sb.p(n == null ? "____ " : String.format("%4d ",n._uid));
    sb.p(" [[");
    for( Node n : _uses ) sb.p(String.format("%4d ",n._uid));
    sb.p("]]  ");
    sb.p(str());
    if( gvn != null ) {
      Type t = gvn.self_type(this);
      sb.s().p(t==null ? "----" : t.toString());
    }
    return sb;
  }
  private void dump(int d, SB sb, BitSet bs, GVNGCM gvn) {
    if( bs.get(_uid) ) return;
    bs.set(_uid);
    dump(d,sb,gvn).nl();
  }
  // Recursively print, up to depth
  private SB dump( int d, SB sb, int max, BitSet bs, GVNGCM gvn, boolean prims ) {
    if( bs.get(_uid) ) return sb;
    bs.set(_uid);
    if( d < max ) {    // Limit at depth
      // Print parser scopes first (deepest)
      for( Node n : _defs ) if( n instanceof ScopeNode ) n.dump(d+1,sb,max,bs,gvn,prims);
      // Print constants early
      for( Node n : _defs ) if( n instanceof ConNode ) n.dump(d+1,sb,max,bs,gvn,prims);
      // Do not recursively print root Scope, nor Unresolved of primitives.
      // These are too common, and uninteresting.
      for( Node n : _defs ) if( n != null && (!prims && n._uid < GVNGCM._INIT0_CNT) ) bs.set(n._uid);
      // Recursively print most of the rest, just not the multi-node combos
      for( Node n : _defs ) if( n != null && !n.is_multi_head() && !n.is_multi_tail() ) n.dump(d+1,sb,max,bs,gvn,prims);
      // Print anything not yet printed, including multi-node combos
      for( Node n : _defs ) if( n != null ) n.dump(d+1,sb,max,bs,gvn,prims);
    }
    // Print multi-node combos all-at-once, including all tails even if they
    // exceed the depth limit by 1.
    Node x = is_multi_tail() ? in(0) : this;
    if( x != null && x.is_multi_head() ) {
      int dx = d+(x==this?0:1);
      // Print all tails paths, all at once - nothing recursively below the tail
      for( Node n : x._uses )
        if( n.is_multi_tail() )
          for( Node m : n._defs )
            m.dump(dx+1,sb,max,bs,gvn,prims);
      if( x==this ) bs.clear(_uid); // Reset for self, so prints right now
      x.dump(dx,sb,bs,gvn); // Conditionally print head of combo
      // Print all combo tails, if not already printed
      if( x!=this ) bs.clear(_uid); // Reset for self, so prints in the mix below
      for( Node n : x._uses ) if( n.is_multi_tail() ) n.dump(dx-1,sb,bs,gvn);
      return sb;
    } else { // Neither combo head nor tail, just print
      return dump(d,sb,gvn).nl();
    }
  }
  private boolean is_multi_head() { return _op==OP_CALLEPI || _op==OP_FUN || _op==OP_IF || _op==OP_LIBCALL || _op==OP_NEW || _op==OP_REGION || _op==OP_START; }
  private boolean is_multi_tail() { return _op==OP_PARM || _op==OP_PHI || _op==OP_PROJ ; }

  public String dumprpo( GVNGCM gvn, boolean prims ) {
    Ary<Node> nodes = new Ary<>(new Node[1],0);
    postorder(nodes,new BitSet());
    // Dump in reverse post order
    SB sb = new SB();
    for( int i=nodes._len-1; i>=0; i-- ) {
      Node n = nodes.at(i);
      if( n._uid <= Env.ALL_CTRL._uid || n._uid >= GVNGCM._INIT0_CNT || prims )
        n.dump(0,sb,gvn).nl();
    }
    return sb.toString();
  }
  private void postorder( Ary<Node> nodes, BitSet bs ) {
    bs.set(_uid);
    for( Node use : _uses ) {
      //if color.get(succ) == 'grey':
      //  print 'CYCLE: {0}-->{1}'.format(node, succ)
      if( !bs.get(use._uid) )
        use.postorder(nodes,bs);
      //color[node] = 'black'
    }
    // Slight PO tweak: heads and tails together.
    if( is_multi_head() )
      for( Node use : _uses )
        if( use.is_multi_tail() )
          nodes.push(use);
    if( !is_multi_tail() ) nodes.push(this);
  }
  
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
  public boolean ideal_impacted_by_losing_uses() { return _op==OP_NEW || _op==OP_FUN || _op==OP_MERGE; }

  // Compute the current best Type for this Node, based on the types of its inputs.
  // May return the local "all_type()", especially if its inputs are in error.
  abstract public Type value(GVNGCM gvn);
  // If an ideal() change breaks type monotonicity, the ideal() call knows this
  // and assures monotonicity happens some other way.
  public boolean monotonicity_assured() { return false; }
    
  // Return any type error message, or null if no error
  public String err(GVNGCM gvn) { return null; }
  
  // Worse-case type for this Node
  public Type all_type() { return Type.ALL; }
  
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
  public Node sharpen( GVNGCM gvn, ScopeNode scope, ScopeNode arm ) { return this; }
    
  // Gather errors; backwards reachable control uses only
  public void walkerr_use( Ary<String> errs, BitSet bs, GVNGCM gvn ) {
    assert !is_dead();
    if( bs.get(_uid) ) return;  // Been there, done that
    bs.set(_uid);               // Only walk once
    if( gvn.type(this) != Type.CTRL ) return; // Ignore non-control
    if( this instanceof ErrNode ) errs.add(((ErrNode)this)._msg); // Gather errors
    for( Node use : _uses )     // Walk control users for more errors
      use.walkerr_use(errs,bs,gvn);
  }
  
  // Gather errors; forwards reachable data uses only.  This is an RPO walk.
  public void walkerr_def( Ary<String> errs0, Ary<String> errs1, Ary<String> errs2, BitSet bs, GVNGCM gvn ) {
    assert !is_dead();
    if( bs.get(_uid) ) return;  // Been there, done that
    bs.set(_uid);               // Only walk once
    if( is_uncalled(gvn) ) return; // Function is a constant, but never executed, do not check for errors
    // Reverse walk: start and exit/return of graph and walk towards root/start.
    for( int i=0; i<_defs._len; i++ ) {
      Node def = _defs.at(i);   // Walk data defs for more errors
      if( def == null || gvn.type(def) == Type.XCTRL ) continue;
      def.walkerr_def(errs0,errs1,errs2,bs,gvn);
    }
    // Post-Order walk: check after walking
    String msg = err(gvn);      // Get any error
    if( msg != null ) {         // Gather errors
      Ary<String> errs;
      if( this instanceof ErrNode ) errs=errs0; // Report ErrNodes first
      // Report bad parms/unresolved calls last, as some other error generally
      // triggered this one.
      else if( this instanceof CallNode && in(1) instanceof UnresolvedNode ) errs=errs2;
      else errs=errs1;          // Other errors (e.g. bad fields for Loads)
      errs.add(msg);
    }
  }
  
  // Gather errors; forwards reachable data uses only
  public void walkerr_gc( Ary<String> errs, BitSet bs, GVNGCM gvn ) {
    if( bs.get(_uid) ) return;  // Been there, done that
    bs.set(_uid);               // Only walk once
    if( is_uncalled(gvn) ) return; // Function is a constant, but never executed, do not check for errors
    if( this instanceof PhiNode &&
        (gvn.type(this).contains(Type.SCALAR) ||
         gvn.type(this).contains(Type.NSCALR)) ) // Cannot have code that deals with unknown-GC-state
      errs.add(((PhiNode)this)._badgc);
    for( int i=0; i<_defs._len; i++ )
      if( in(i) != null ) in(i).walkerr_gc(errs,bs,gvn);
  }
  public boolean is_dead() { return _uses == null; }
  public void set_dead( ) { _defs = _uses = null; }   // TODO: Poor-mans indication of a dead node, probably needs to recycle these...

  // Overridden in subclasses that return TypeTuple value types.  Such nodes
  // are always followed by ProjNodes to break out the tuple slices.  If the
  // node optimizes, each ProjNode becomes a copy of some other value... based
  // on the ProjNode index
  public Node is_copy(GVNGCM gvn, int idx) { return null; }

  // True if epilog or function is uncalled (but possibly returned or stored as
  // a constant).  Such code is not searched for errors.
  boolean is_uncalled(GVNGCM gvn) { return false; }
  
  // Only true for some RetNodes and FunNodes
  public boolean is_forward_ref() { return false; }
  
  // Walk a subset of the dominator tree, looking for the last place (highest
  // in tree) this predicate passes, or null if it never does.  
  Node walk_dom_last(Predicate<Node> P) {
    if( this==Env.START ) return null; // Walked off the top
    assert in(0) != null;       // All default control nodes pass ctrl in slot 0
    Node n = in(0).walk_dom_last(P);
    if( n != null ) return n;   // Take last answer first
    return P.test(this) ? this : null;
  }
  
}
