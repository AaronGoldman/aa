package com.cliffc.aa;

import com.cliffc.aa.node.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Ary;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

// Global Value Numbering, Global Code Motion
public class GVNGCM {
  // Unique dense node-numbering
  private static int CNT;
  private static BitSet _live = new BitSet();  // Conservative approximation of live; due to loops some things may be marked live, but are dead

  public static int uid() { assert CNT < 100000 : "infinite node create loop"; _live.set(CNT);  return CNT++; }

  public int _opt_mode;         // 0 - Parse (discovery), 1 - iter, 2 - gcp/opto

  // Iterative worklist
  private Ary<Node> _work = new Ary<>(new Node[1], 0);
  private BitSet _wrk_bits = new BitSet();

  public void add_work( Node n ) { if( !_wrk_bits.get(n._uid) && n._keep==0 ) add_work0(n); }
  private <N extends Node> N add_work0( N n ) {
    _work.add(n);               // These need to be visited later
    _wrk_bits.set(n._uid);
    return n;
  }

  // A second worklist, for code-expanding and thus lower priority work.
  // Inlining happens off this worklist, once the main worklist runs dry.
  private Ary<Node> _work2 = new Ary<>(new Node[1], 0);
  private BitSet _wrk2_bits = new BitSet();
  public void add_work2( Node n ) {
    if( !_wrk2_bits.get(n._uid) ) {
      _work2.add(n);
      _wrk2_bits.set(n._uid);
    }
  }

  // Array of types representing current node types.  Essentially a throw-away
  // temp extra field on Nodes.  It is either bottom-up, conservatively correct
  // or top-down and optimistic.
  private Ary<Type> _ts = new Ary<>(new Type[1],0);

  // Global expressions, to remove redundant Nodes
  private ConcurrentHashMap<Node,Node> _vals = new ConcurrentHashMap<>();

  public String dump( Node n, int max ) { return n.dump(max,this); }

  // Initial state after loading e.g. primitives & boot libs.  Record state
  // here, so can reset to here cheaply and parse again.
  public static int _INIT0_CNT;
  private static Node[] _INIT0_NODES;
  void init0() {
    assert _live.get(CNT-1) && !_live.get(CNT) && _work._len==0 && _wrk_bits.isEmpty() && _ts._len==CNT;
    _INIT0_CNT=CNT;
    _INIT0_NODES = _vals.keySet().toArray(new Node[0]);
    for( Node n : _INIT0_NODES ) assert !n.is_dead();
  }
  // Reset is called after a top-level exec exits (e.g. junits) with no parse
  // state left alive.  NOT called after a line in the REPL or a user-call to
  // "eval" as user state carries on.
  void reset_to_init0() {
    while( _work._len > 0 ) {   // Can be a few leftover dead bits...
      Node n = _work.pop();     // from top-level parse killing result...
      _wrk_bits.clear(n._uid);  // after getting type to return
      if( n == Env.MEM_0 ) continue; // Do not nuke top-level frame with primitives
      // Unreachable loops can be dead; break the loop and delete
      for( int i=0; !n.is_dead() && i<n._defs._len; i++ )
        if( n.in(i)!=null )     // Start breaking edges
          set_def_reg(n,i,null);
    }
    CNT = _INIT0_CNT;
    _live.clear();  _live.set(0,_INIT0_CNT);
    _ts.set_len(_INIT0_CNT);
    _vals.clear();
    for( Node n : _INIT0_NODES ) {
      for( int i=0; i<n._uses._len; i++ )
        if( n._uses.at(i)._uid >= CNT )
          n._uses.del(i--);
      for( int i=0; i<n._defs._len; i++ )
        if( n._defs.at(i) != null && n._defs.at(i)._uid >= CNT )
          { assert n instanceof FunNode || n instanceof ParmNode; n._defs.del(i--); }
      assert !n.is_dead();
      _vals.put(n,n);
    }
    for( Node n : _INIT0_NODES ) // Reset types
      _ts.set(n._uid,n.value(this));
    for( Node n : _INIT0_NODES ) // Reset types
      _ts.set(n._uid,n.value(this));
  }

  public Type type( Node n ) {
    Type t = n._uid < _ts._len ? _ts._es[n._uid] : null;
    if( t != null ) return t;
    t = n.all_type();       // If no type yet, defaults to the pessimistic type
    return _ts.setX(n._uid,t);
  }
  public void setype( Node n, Type t ) {
    assert t != null;
    _ts.setX(n._uid,t);
  }
  // Return the prior self_type during the value() call, without installing a
  // new type.
  public Type self_type( Node n ) {
    return n._uid < _ts._len ? _ts._es[n._uid] : n.all_type();
  }
  // Make globally shared common ConNode for this type.
  public @NotNull ConNode con( Type t ) {
    // Check for a function constant, and return the globally shared common
    // FunPtrNode instead.
    if( t instanceof TypeFunPtr && t.is_con() )
      return FunNode.find_fidx(((TypeFunPtr)t).fidx()).ret().funptr();
    ConNode con = new ConNode<>(t);
    Node con2 = _vals.get(con);
    if( con2 != null ) { kill0(con); return (ConNode)con2; } // TODO: con goes dead, should be recycled
    setype(con,t);
    _vals.put(con,con);
    return con;
  }

  // Record a Node, but do not optimize it for value and ideal calls, as it is
  // mid-construction from the parser.  Any function call with yet-to-be-parsed
  // call sites, and any loop top with an unparsed backedge needs to use this.
  public <N extends Node> N init( N n ) {
    assert n._uses._len==0;
    return init0(n);
  }
  <N extends Node> N init0( N n ) {
    setype(n,n.all_type());
    _vals.put(n,n);
    return add_work0(n);
  }

  // Add a new def to 'n', changing its hash - so rehash it
  public void add_def( Node n, Node def ) {
    Node x = _vals.remove(n);
    assert x == n || (x==null && _wrk_bits.get(n._uid));
    n.add_def(def);
    _vals.put(n,n);
    add_work(n);
  }
  public void remove( Node n, int idx ) {
    Node x = _vals.remove(n);
    assert x == n || (x==null && _wrk_bits.get(n._uid));
    n.remove(idx,this);
    _vals.put(n,n);
    add_work(n);
  }

  // True if in _ts and _vals, false otherwise
  public boolean touched( Node n ) { return _ts.atX(n._uid)!=null; }

  // Remove from GVN structures.  Used rarely for whole-merge changes
  public void unreg( Node n ) { assert !check_new(n); unreg0(n); }
  private Node unreg0( Node n ) {
    _ts.set(n._uid,null);       // Remove from type system
    _vals.remove(n);            // Remove from GVN
    // TODO: Remove from worklist also
    return n;
  }

  // Used rarely for whole-merge changes
  public void rereg( Node n, Type oldt ) {
    assert !check_opt(n);
    setype(n,oldt);
    _vals.put(n,n);
    add_work0(n);
  }

  // Hack an edge, updating GVN as needed
  public void set_def_reg(Node n, int idx, Node def) {
    _vals.remove(n);            // Remove from GVN
    n.set_def(idx,def,this);    // Hack edge
    if( n.is_dead() ) return;
    assert !check_gvn(n,false); // Check not in GVN table after hack
    _vals.put(n,n);             // Back in GVN table
    add_work(n);
  }
  public void remove_reg(Node n, int idx) {
    _vals.remove(n);            // Remove from GVN
    n.remove(idx,this);         // Hack edge
    if( n.is_dead() ) return;
    assert !check_gvn(n,false); // Check not in GVN table after hack
    _vals.put(n,n);             // Back in GVN table
    add_work(n);
  }

  // Node new to GVN and unregistered, or old and registered
  private boolean check_new(Node n) {
    if( check_opt(n) ) return false; // Not new
    assert n._uses._len==0;          // New, so no uses
    return true;
  }
  // Node is in the type table and GVN hash table
  private boolean check_opt(Node n) {
    if( touched(n) ) {          // First & only test: in type table or not
      assert (n instanceof ScopeNode) || _wrk_bits.get(n._uid)  || check_gvn(n,true); // Check also in GVN table
      return true;              // Yes in both type table and GVN table
    }
    assert !check_gvn(n,false); // Check also not in GVN table
    return false;               // Node not in tables
  }

  private boolean check_gvn( Node n, boolean expect ) {
    Node x = _vals.get(n), old=null;
    if( x == n ) {              // Found in table
      assert expect;            // Expected to be found
      return true;              // Definitely in table
    }
    boolean found = false;
    for( Node o : _vals.keySet() ) if( (old=o)._uid == n._uid ) { found=true; break; }
    assert found == expect || _wrk_bits.get(n._uid) : "Found but not expected: "+old.toString(); // Expected in table or on worklist
    return false;               // Not in table
  }

  // Apply graph-rewrite rules on new nodes (those with no users and kept alive
  // for the parser).  Return a node registered with GVN that is possibly "more
  // ideal" than what was before.
  public Node xform( Node n ) {
    assert check_new(n);

    // Try generic graph reshaping, looping till no-progress.
    int cnt=0;  Node x;        // Progress bit
    while( (x = n.ideal(this)) != null ) {
      if( x != n ) {            // Different return, so delete original dead node
        x.keep();               // Keep alive during deletion of n
        kill_new(n); // n was new, replaced so immediately recycle n and dead subgraph
        n = x.unhook();         // Remove keep-alive
      }
      if( !check_new(n) ) return n; // If the replacement is old, no need to re-ideal
      cnt++; assert cnt < 1000;     // Catch infinite ideal-loops
    }
    // Compute a type for n
    Type t = n.value(this);              // Get best type
    // Replace with a constant, if possible
    if( t.may_be_con() && !(n instanceof ConNode) && n._keep==0 )
      { kill_new(n); return con(t); }
    // Global Value Numbering
    x = _vals.putIfAbsent(n,n);
    if( x != null ) { kill_new(n); return x; }
    // Record type for n; n is "in the system" now
    setype(n,t);                         // Set it in
    // TODO: If x is a TypeNode, capture any more-precise type permanently into Node
    return n;
  }

  // Recursively kill off a new dead node, which might make lots of other nodes
  // go dead.  Since its new, no need to remove from GVN system.
  public void kill_new( Node n ) { assert check_new(n);  kill0(n); }
  // Recursively kill off a dead node, which might make lots of other nodes go dead
  public void kill( Node n ) {  kill0(unreg0(n)); }
  // Version for never-GVN'd; common for e.g. constants to die early or
  // RootNode, and some other make-and-toss Nodes.
  private void kill0( Node n ) {
    assert n._uses._len==0 && n._keep==0;
    for( int i=0; i<n._defs._len; i++ ) {
      Node def = n._defs.at(i);
      if( def != null && def.ideal_impacted_by_losing_uses() ) add_work(def);
      n.set_def(i,null,this);   // Recursively destroy dead nodes
    }
    n.set_dead();               // n is officially dead now
    _live.clear(n._uid);
    if( n._uid==CNT-1 ) {       // Roll back unused node indices
      while( !_live.get(CNT-1) ) CNT--;
    }
  }

  private void xform_old( Node old ) {
    Node nnn = xform_old0(old);
    if( nnn==null ) return;
    if( nnn == old ) {          // Progress, but not replacement
      for( Node use : old._uses ) add_work(use);
      add_work(old);            // Re-run old, until no progress
      return;
    }
    if( check_new(nnn) )        // If new, replace back in GVN
      rereg(nnn,nnn.value(this));
    if( !old.is_dead() ) { // if old is being replaced, it got removed from GVN table and types table.
      assert !check_opt(old);
      subsume(old,nnn);
    }
  }

  // Replace with a ConNode iff
  // - Not already a ConNode AND
  // - Not an ErrNode AND
  // - Type isa Con
  private static boolean replace_con(Type t, Node n) {
    if( n instanceof ConNode || n instanceof ErrNode )
      return false; // Already a constant, or never touch an ErrNode
    return t.is_con(); // Replace with a ConNode
  }

  /** Look for a better version of 'n'.  Can change n's defs via the ideal()
   *  call, including making new nodes.  Can replace 'n' wholly, with n's uses
   *  now pointing at the replacement.
   *  @param n Node to be idealized; already in GVN
   *  @return null for no-change, or a better version of n, already in GVN */
  private Node xform_old0( Node n ) {
    assert touched(n);         // Node is in type tables, but might be already out of GVN
    _vals.remove(n);           // Remove before modifying edges (and thus hash)
    Type oldt = type(n);       // Get old type
    _ts._es[n._uid] = null;    // Remove from types, mostly for asserts
    assert !check_opt(n);      // Not in system now
    if( replace_con(oldt,n) )
      return con(oldt);        // Dead-on-Entry, common when called from GCP
    // Try generic graph reshaping
    Node y = n.ideal(this);
    if( y != null && y != n ) return y;  // Progress with some new node
    if( y != null && y.is_dead() ) return null;
    // Either no-progress, or progress and need to re-insert n back into system
    _ts._es[n._uid] = oldt;     // Restore old type, in case we recursively ask for it
    Type t = n.value(this);     // Get best type
    _ts._es[n._uid] = null;     // Remove in case we replace it
    // Replace with a constant, if possible
    if( replace_con(t,n) )
      return con(t);            // Constant replacement
    // Global Value Numbering
    Node z = _vals.putIfAbsent(n,n);
    if( z != null ) return z;
    // Record type for n; n is "in the system" now
    setype(n,t);                // Set it in
    // TODO: If x is a TypeNode, capture any more-precise type permanently into Node
    return oldt == t && y==null ? null : n; // Progress if types improved
  }

  // Replace, but do not delete old.  Really used to insert a node in front of old.
  public void replace( Node old, Node nnn ) {
    while( old._uses._len > 0 ) {
      Node u = old._uses.del(0);  // Old use
      boolean was = touched(u);
      _vals.remove(u);  // Use is about to change edges; remove from type table
      u._defs.replace(old,nnn); // was old now nnn
      nnn._uses.add(u);
      if( was ) {            // If was in GVN
        _vals.put(u,u);      // Back in the table, since its still in the graph
        add_work(u);         // And put on worklist, to get re-visited
      }
    }
  }

  // Complete replacement; point uses to 'nnn'.  The goal is to completely replace 'old'.
  public void subsume( Node old, Node nnn ) {
    replace(old,nnn);
    nnn.keep();                 // Keep-alive
    kill(old);                  // Delete the old n, and anything it uses
    nnn.unhook();               // Remove keep-alive
  }

  // Once the program is complete, any time anything is on the worklist we can
  // always conservatively iterate on it.
  public boolean _small_work;
  void iter() {
    _opt_mode = 1;
    // As a modest debugging convenience, avoid inlining (which blows up the
    // graph) until other optimizations are done.  Gather the possible inline
    // requests and set them aside until the main list is empty, then work down
    // the inline list.
    int cnt=0;
    while( (_small_work=_work._len > 0) || _work2._len > 0 ) {
      Node n = (_small_work ? _work : _work2).pop(); // Pull from main worklist before functions
      (_small_work ? _wrk_bits : _wrk2_bits).clear(n._uid);
      if( n.is_dead() || n._keep!=0 ) continue;
      if( n._uses._len==0 ) kill(n);
      else xform_old(n);
      cnt++; assert cnt < 10000; // Catch infinite ideal-loops
    }
  }

  // Global Optimistic Constant Propagation.  Passed in the final program state
  // (including any return result, i/o & memory state).  Returns the most-precise
  // types possible, and replaces constants types with constants.
  //
  // Besides the obvious GCP algorithm (and the type-precision that results
  // from the analysis), GCP does a few more things.
  //
  // GCP builds an explicit Call-Graph.  Before GCP
  // not all callers are known and this is approximated by being called by
  // ALL_CTRL, a ConNode of Type CTRL, as a permanently available unknown
  // caller.  If the whole program is available to us, the we can compute all
  // callers conservatively and precisely - we may have extra never-taken
  // caller/callee edges, but no missing caller/callee edges.  These edges are
  // virtual going in (and represented by ALL_CTRL); we can remove the ALL_CTRL
  // path and add in physical edges in the CallNode.value() call while whole-
  // program GCP is active.
  void gcp(ScopeNode rez ) {
    _opt_mode = 2;
    // Set all types to null (except primitives); null is the visit flag when
    // setting types to their highest value.
    Arrays.fill(_ts._es,0,Env.LAST_START_UID,null);
    Arrays.fill(_ts._es,_INIT0_CNT,_ts._len,null);
    // Set all types to all_type().dual(), their most optimistic type,
    // and prime the worklist.
    walk_initype( Env.START);

    // Collect unresolved calls, and verify they get resolved.
    Ary<CallNode> ambi_calls = new Ary<>(new CallNode[1],0);

    // Repeat, if we remove some ambiguous choices, and keep falling until the
    // graph stabilizes without ambiguity.
    while( _work._len > 0 ) {
      // Analysis phase.
      // Work down list until all reachable nodes types quit falling
      while( _work._len > 0 ) {
        Node n = _work.pop();
        _wrk_bits.clear(n._uid);
        if( n.is_dead() ) continue; // Can be dead functions after removing ambiguous calls
        if( Env.LAST_START_UID <= n._uid && n._uid < _INIT0_CNT ) continue; // Ignore primitives (type is unchanged and conservative)
        if( n instanceof CallNode ) {
          CallNode call = (CallNode)n;
          BitsFun fidxs = call.fidxs(this);
          if( fidxs != null && fidxs.above_center() && ambi_calls.find(call)== -1 )
            ambi_calls.add((CallNode)n); // Track ambiguous calls
        }
        Type ot = type(n);       // Old type
        Type nt = n.value(this); // New type
        assert ot.isa(nt);       // Types only fall monotonically
        if( ot != nt ) {         // Progress
          _ts.setX(n._uid,nt);   // Record progress
          for( Node use : n._uses ) {
            if( use==n ) continue;        // Stop self-cycle (not legit, but happens during debugging)
            if( use.all_type() != type(use)) // Minor optimization: If not already at bottom
              add_work(use); // Re-run users to check for progress
            // When new control paths appear on Regions, the Region stays the
            // same type (Ctrl) but the Phis must merge new values.
            if( use instanceof RegionNode )
              for( Node phi : use._uses ) if( phi != n ) add_work(phi);
          }
        }
      }

      // Remove CallNode ambiguity after worklist runs dry
      for( int i=0; i<ambi_calls.len(); i++ ) {
        CallNode call = ambi_calls.at(i);
        if( call.is_dead() ) ambi_calls.del(i--); // Remove from worklist
        else {
          FunPtrNode fun = call.resolve(this);
          if( fun != null ) {          // Unresolved gets left on worklist
            call.set_fun_reg(fun,this); // Set resolved edge
            ambi_calls.del(i--);       // Remove from worklist
          }
        }
      }
    }

    // Revisit the entire reachable program, as ideal calls may do something
    // with the maximally lifted types.
    walk_opt(rez);
    walk_dead(Env.START);
  }

  // Forward reachable walk, setting all_type.dual (except Start) and priming
  // the worklist for nodes that are not above centerline.
  private void walk_initype( Node n ) {
    if( n==null || touched(n) ) return; // Been there, done that
    Type startype = n.all_type().startype();
    setype(n,startype);
    add_work(n);
    // Walk reachable graph
    for( Node use : n._uses ) walk_initype(use);
    for( Node def : n._defs ) walk_initype(def);
  }

  // GCP optimizations on the live subgraph
  private void walk_opt( Node n ) {
    assert !n.is_dead();
    if( _wrk_bits.get(n._uid) ) return; // Been there, done that
    if( n==Env.START ) return;          // Top-level scope
    add_work(n);                        // Only walk once
    // Replace with a constant, if possible
    Type t = type(n);
    if( replace_con(t,n) ) {
      subsume(n,con(t));        // Constant replacement
      return;
    }
    // Functions can sharpen return value
    if( n instanceof FunNode && n._uid >= _INIT0_CNT ) {
      FunNode fun = (FunNode)n;
      RetNode ret = fun.ret();
      if( type(fun)==Type.CTRL && !fun.is_forward_ref() &&
          type(ret.ctl()) == Type.CTRL ) { // never-return function (maybe never called?)
        Type tret = ((TypeTuple)type(ret)).at(2);
        if( fun._tf._ret != tret && // can sharpen function return
            tret.isa(fun._tf._ret) ) { // Only if sharpened (might not be true for errors)
          unreg(fun);
          fun._tf = TypeFunPtr.make(fun._tf.fidxs(),fun._tf._args,tret);
          rereg(fun,Type.CTRL);
        }
      }
    }
    // All (live) Call ambiguity has been resolved
    if( n instanceof CallNode && type(n.in(0))==Type.CTRL ) {
      BitsFun fidxs = ((CallNode)n).fidxs(this);
      assert n.err(this) != null || // Call is in-error OR
        !fidxs.above_center() || fidxs==BitsFun.EMPTY;
    }

    // Walk reachable graph
    for( Node def : n._defs )
      if( def != null )
        walk_opt(def);
  }

  // Walk dead control and place on worklist
  private void walk_dead( Node n ) {
    assert !n.is_dead();
    if( _wrk_bits.get(n._uid) ) return; // Been there, done that
    if( n._uid != 0 && n._uid <= _INIT0_CNT ) return;  // Not primitives
    add_work(n);                        // Only walk once
    for( Node use : n._uses ) walk_dead(use);
  }
}
