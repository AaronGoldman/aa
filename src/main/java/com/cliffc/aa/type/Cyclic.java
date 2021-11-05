package com.cliffc.aa.type;

import com.cliffc.aa.AA;
import com.cliffc.aa.util.*;

import java.util.Set;
import java.util.function.*;


// Algorithm for minimizing a not-yet-interned graph of Types
interface Cyclic {

  // Type is cyclic
  boolean cyclic();
  void set_cyclic();

  // Walk the outgoing edges 1-step only, mapping and reducing a result
  void walk1( BiFunction<Type,String,Type> map );

  // Update the edges in-place
  void walk_update( UnaryOperator<Type> map );

  // Install a cyclic structure.  'head' is not interned and points to a
  // (possibly cyclic) graph of not-interned Types.  Minimize the graph, set
  // the hashes everywhere, check for a prior existing Type.  Return a prior,
  // or else set all the duals and intern the entire graph.
  @SuppressWarnings("unchecked")
  static <T extends Type> T install( T head ) {
    Type.RECURSIVE_MEET++;
    _reachable(head,true);      // Compute 1st-cut reachable
    head = _dfa_min(head);
    _reachable(head,false);     // Recompute reachable; skip interned; probably shrinks
    assert check_uf();
    UF.clear();
    Type.RECURSIVE_MEET--;

    // Set cyclic bits for faster equals/meets.
    assert CSTACK.isEmpty() && CVISIT.cardinality()==0;
    _set_cyclic(head);
    assert CSTACK.isEmpty();   CVISIT.clear();

    // Check for dups.  If found, delete entire cycle, and return original.
    T old = (T)head.intern_lookup();
    if( old != null ) {         // Found prior interned cycle
      for( Type t : REACHABLE ) t.free(null); // Free entire new cycle
      return old;               // Return original cycle
    }
    // Complete cyclic dual
    head.rdual();
    // Insert all members of the cycle into the hashcons.  If self-symmetric,
    // also replace entire cycle with self at each point.
    for( Type t : REACHABLE )
      if( !t.interned() )
        if( t.retern() != t._dual ) t._dual.retern();
    // Return new interned cycle
    return head;
  }


  // -----------------------------------------------------------------
  // Set the cyclic bit on structs in cycles.  Can be handed an arbitrary
  // graph, including a DAG of unrelated Strongly Connected Components.

  // Almost classic cycle-finding algorithm but since Structs have labeled
  // out-edges (with field names), we can have multiple output edges from the
  // same node (struct) to the same TypeMemPtr.  The classic cycle-finders do
  // not work with multi-edges.
  Ary<Type> CSTACK = new Ary<>(Type.class);
  VBitSet CVISIT = new VBitSet();
  static void _set_cyclic(Type t ) {
    assert t._hash==t.compute_hash(); // Hashes already set by shrink
    if( t.interned() ) return;  // Already interned (so hashed, cyclic set, etc)
    if( CVISIT.test(t._uid) ) { // If visiting again... have found a cycle t->....->t
      // All on the stack are flagged as being part of a cycle
      int i=CSTACK._len-1;
      while( i >= 0 && CSTACK.at(i)!=t ) i--;
      if( i== -1 ) return;  // Due to multi-edges, we might not find if dupped, so just ignore
      for( ; i < CSTACK._len; i++ ) { // Set cyclic bit
        Type t2 = CSTACK.at(i);
        if( t2 instanceof Cyclic ) ((Cyclic)t2).set_cyclic();
      }
      return;
    }
    CSTACK.push(t);              // Push on stack, in case a cycle is found
    switch( t._type ) {
    case Type.TMEMPTR ->   _set_cyclic(((TypeMemPtr) t)._obj);
    case Type.TFUNPTR -> { _set_cyclic(((TypeFunPtr) t)._dsp); _set_cyclic(((TypeFunPtr) t)._ret); }
    case Type.TFLD    ->   _set_cyclic(((TypeFld   ) t)._t  );
    case Type.TSTRUCT -> { CVISIT.set(t._uid);  for( TypeFld fld : ((TypeStruct) t).flds() ) _set_cyclic(fld);  }
    default -> throw AA.unimpl();
    }
    CSTACK.pop();               // Pop, not part of another's cycle
  }

  // -----------------------------------------------------------------
  // Support Disjoint-Set Union-Find on Types
  NonBlockingHashMapLong<Type> UF = new NonBlockingHashMapLong<>();
  @SuppressWarnings("unchecked")
  static <T extends Type> T ufind(T t) {
    T t0 = (T)UF.get(t._uid), tu;
    if( t0 == null ) return t;  // One step, hit end of line
    // Find end of U-F line
    while( (tu = (T)UF.get(t0._uid)) != null ) t0=tu;
    // Set whole line to 1-step end of line
    while( (tu = (T)UF.get(t ._uid)) != null ) { assert t._uid != t0._uid; UF.put(t._uid,t0); t=tu; }
    return t0;
  }
  static <T extends Type> T union( T lost, T kept) {
    if( lost == kept ) return kept;
    assert !lost.interned();
    assert UF.get(lost._uid)==null && UF.get(kept._uid)==null;
    assert lost._uid != kept._uid;
    UF.put(lost._uid,kept);
    return kept;
  }

  // Walk, looking for not-minimal.  Happens during 'approx' which might
  // require running several rounds of 'shrink' to fold everything up.
  static boolean check_uf() {
    int err=0;
    NonBlockingHashMap<Type,Type> ss = new NonBlockingHashMap<>();
    for( Type t : REACHABLE ) {
      Type tt;
      if( ss.get(t) != null || // Found unresolved dup; ts0.equals(ts1) but ts0!=ts1
          ((tt = t.intern_lookup()) != null && tt != t) ||
          ufind(t) != t )
        err++;
      ss.put(t,t);
    }
    return err == 0;
  }

  // -----------------------------------------------------------------
  // Reachable collection of Types that form cycles: TypeMemPtr, TypeFunPtr,
  // TypeFld, TypeStruct, and anything not interned reachable from them.
  Ary<Type> REACHABLE = new Ary<>(new Type[1],0);
  BitSetSparse ON_REACH = new BitSetSparse();
  private static void _reachable(Type head, final boolean also_interned) {
    // Efficient 1-pass linear-time algo: the REACHABLE set keeps growing, and
    // idx points to the next not-scanned-but-reached Type.
    REACHABLE.clear();
    ON_REACH.clear();
    _push(head, also_interned);
    for( int idx=0; idx < REACHABLE._len; idx++ )
      ((Cyclic)REACHABLE.at(idx)).walk1((t,label) -> _push(t,also_interned));
  }
  private static Type _push( Type t, boolean also_interned ) {
    if( !ON_REACH.tset(t._uid) &&
        (!t.interned() ||
         // Optionally, also interned cycles
         (also_interned && t instanceof Cyclic && ((Cyclic)t).cyclic())))
      REACHABLE.push(t);
    return t;
  }

  // -----------------------------------------------------------------
  // This is a Type minimization algorithm done "bottom up" or pessimistically.
  // It repeatedly finds instances of local duplication and removes them,
  // repeating until hitting a fixed point.  Local dups include any already
  // interned Types, or DUPS (local interning or hash-equivalence) or a UF hit.
  // Computes the final hash code as part of intern checking.
  IHashMap DUPS = new IHashMap();
  private static <T extends Type> T _shrink(T nt) {
    assert DUPS.isEmpty();
    // Set all hashes.  Hash recursion stops at TypeStructs, so do them first,
    // then do dependent hashes.
    for( Type t : REACHABLE ) if( t instanceof TypeStruct ) t.set_hash();
    for( Type t : REACHABLE ) if( t instanceof TypeMemPtr ) t.set_hash();
    for( Type t : REACHABLE ) if( t instanceof TypeFunPtr ) t.set_hash();
    for( Type t : REACHABLE ) t.set_hash();    // And all the rest.

    // Need back-edges to do this iteratively in 1 pass.  This algo just sweeps
    // until no more progress, but with generic looping instead of recursion.
    boolean progress = true;
    while( progress ) {
      progress = false;
      DUPS.clear();
      for( Type t : REACHABLE ) {
        Type t0 = ufind(t);
        Type t1 = t0.intern_lookup();
        if( t1==null ) t1 = DUPS.get(t0);
        if( t1 != null ) t1 = ufind(t1);
        if( t1 == t0 ) continue; // This one is already interned
        if( t1 != null ) { union(t0,t1); progress = true; continue; }

        switch( t._type ) {
        case Type.TMEMPTR:      // Update TypeMemPtr internal field
          TypeMemPtr tm = (TypeMemPtr)t0;
          TypeObj t4 = tm._obj;
          TypeObj t5 = ufind(t4);
          if( t4 != t5 ) {
            tm._obj = t5;
            progress |= post_mod(tm);
            if( !t5.interned() ) REACHABLE.push(t5);
          }
          break;
        case Type.TFUNPTR:      // Update TypeFunPtr internal field
          boolean fprogress=false;
          TypeFunPtr tfptr = (TypeFunPtr)t0;
          Type t6 = tfptr._dsp;
          Type t7 = ufind(t6);
          if( t6 != t7 ) {
            tfptr._dsp = t7;
            fprogress = true;
            if( !t7.interned() ) REACHABLE.push(t7);
          }
          t6 = tfptr._ret;
          t7 = ufind(t6);
          if( t6 != t7 ) {
            tfptr._ret = t7;
            fprogress = true;
            if( !t7.interned() ) REACHABLE.push(t7);
          }
          if( fprogress ) progress |= post_mod(tfptr);
          break;
        case Type.TSTRUCT:      // Update all TypeStruct fields
          TypeStruct ts = (TypeStruct)t0;
          for( TypeFld tfld : ts.flds() ) {
            TypeFld tfld2 = ufind(tfld);
            if( tfld != tfld2 ) {
              progress = true;
              ts.set_fld(tfld2);
            }
          }
          break;
        case Type.TFLD:         // Update all TFlds
          TypeFld tfld = (TypeFld)t0;
          Type tft = tfld._t, t2 = ufind(tft);
          if( tft != t2 ) {
            progress = true;
            int old_hash = tfld._hash;
            tfld._t = t2;
            assert old_hash == tfld.compute_hash();
          }
          break;

        default: break;
        }
        DUPS.put(t0);
      }
    }
    DUPS.clear();
    return ufind(nt);
  }

  // Set hash after field mod, and re-install in dups
  private static boolean post_mod(Type t) {
    t._hash = t.compute_hash();
    DUPS.put(t);
    return true;
  }


  // --------------------------------------------------------------------------
  // This is a Type minimization algorithm done "top down" or optimistically.
  // It is loosely based on Hopcroft DFA minimization or Click thesis.  Edges
  // are labeled via Strings (struct labels) instead of being a small count so
  // the inner loops are reordered to take advantage.

  // Type Partitions based on Click thesis: groups of equivalent Types, that
  // have equal static properties, and equivalent Type edges.
  class Partition implements IntSupplier {
    // Static NBMHL from Type.uid to Partitions
    static final NonBlockingHashMapLong<Partition> TYPE2PART = new NonBlockingHashMapLong<>();
    // All initial Partitions, in an iterable
    static final Ary<Partition> PARTS = new Ary<>(Partition.class);
    // Free list
    static private final Ary<Partition> FREES = new Ary<>(Partition.class);

    static Partition malloc() {
      Partition P = FREES.isEmpty() ? new Partition() : FREES.pop();
      return PARTS.push(P);
    }

    // Reset for another round of minimization
    static void clear() {
      TYPE2PART.clear();
      for( Partition P : PARTS )  P.clear0();
      FREES.addAll(PARTS);
      PARTS.clear();
    }

    // Polite tracking for partitions
    private static int CNT=1;
    int _uid = CNT++;
    @Override public int getAsInt() { return _uid; }
    // All the Types in this partition
    private final Ary<Type> _ts = new Ary<>(new Type[1],0);
    // All the Type uids, touched in this pass
    private final BitSetSparse _touched = new BitSetSparse();
    // All the edge labels
    private final NonBlockingHashMap<String,String> _edges = new NonBlockingHashMap<>();
    private void clear0() {
      _ts.clear();
      assert _touched.cardinality()==0;
      _edges.clear();
    }

    // Number of Types in partition
    int len() { return _ts._len; }
    // Add type t to Partition, track the edge set
    void add( Type t) {
      _ts.add(t);
      TYPE2PART.put(t._uid,this);
      var edges = DefUse.edges(t);
      if( edges != null )
        for( String s : edges )
          _edges.put(s,"");
    }
    // Delete and return the ith type.  Does not update the edges list, which
    // may contain edge labels that no longer point to any member of the part.
    Type del(int idx) {
      Type t = _ts.at(idx);
      TYPE2PART.remove(t._uid);
      return _ts.del(idx);
    }
    // Get head/slot-0 Type
    Type head() { return _ts.at(0); }
    void set_head(Type t) { _ts.set(0,t); }

    // Get the partition head value for type t, if it exists, or just t
    static Type head(Type t) {
      Partition P = TYPE2PART.get(t._uid);
      return P==null ? t : P.head();
    }


    // Cause_Splits from Click thesis.
    // Original loop ordering; need to have the set of edge labels
    // Loops over all outgoing partition edges once per edge label.
    //   for-all edge labels:
    //     for-all Tx in P
    //       for-all Ty of Tx.uses[edge]
    //         assert Ty[edge]==Tx // Edge going from Y to X
    //         Py = partition(Ty)
    //         touched.set(Py);// track partitions Py
    //         Py.touched.set(Py) // track types in Py that are touched
    //   for-all Pz in touched
    //     if Pz.touched!=Pz  // did not touch them all
    //       Split(Pz,Pz.touched)
    //     clear Pz.touched
    //   touched.clear
    private static final Work<Partition> TOUCHED = new Work<>();
    void do_split() {
      assert TOUCHED.isEmpty();
      for( String edge : _edges.keySet() ) {
        boolean edge_alive=false; // Lazily reduce the edge set
        for( Type tdef : _ts ) {
          Ary<Type> tuses = DefUse.uses(edge,tdef);
          if( tuses!=null ) {
            edge_alive=true;
            for( Type tuse : tuses ) {
              Partition Puse = TYPE2PART.get(tuse._uid);
              if( Puse !=null && Puse.len() > 1 ) // Length-1 partitions cannot be split
                TOUCHED.add(Puse)._touched.tset(tuse._uid);
            }
          }
        }
        if( !edge_alive )
          _edges.remove(edge);
      }

      Partition Pz;
      while( (Pz=TOUCHED.pop())!=null ) { // For all touched partitions
        if( Pz._touched.cardinality() < Pz.len() ) { // Touched all members?
          Partition P2 = Pz.split();
          WORK.add(WORK.on(Pz) || Pz.len() > P2.len() ? P2 : Pz);
        }
        Pz._touched.clear();
      }
    }

    // Split a partition in two based on the _touched set.
    Partition split() {
      assert 1 <= _touched.cardinality() && _touched.cardinality() < _ts._len;
      Partition P2 = malloc();
      for( int i=0; i<_ts._len; i++ )
        if( _touched.tset(_ts.at(i)._uid) ) // Touched; move element
          P2.add(del(i--));                 // Delete from this, add to P2
      assert len() >= 1 && P2.len() >= 1;
      return P2;
    }
  }



  // Pick initial partitions for Types based on static Type properties.
  // This uses an alternative hash and equals functions.
  class SType {
    static private final NonBlockingHashMap<SType,Partition> TYPE2INITPART = new NonBlockingHashMap<>();
    static private SType KEY = new SType();

    // All types put in partitions based on static (no edges) properties:
    // Private one for interned, one for each _type, _any, and _aliases,
    // _fidxs or field names/_open/_use.  Put all partitions on worklist,
    // then repeat cause_splits.
    static Partition init_part(Type t) {
      KEY._t = t;             // Put Type in the prototype SType
      Partition P = TYPE2INITPART.get(KEY);
      if( P==null ) {         // No matching SType, so needs a new partition
        P = Partition.malloc();
        TYPE2INITPART.put(KEY,P); // Install SType to Partition
        KEY = malloc();       // Return a new prototype SType for next lookup
      }
      return P;
    }

    static private SType malloc() { return new SType(); }
    private void free() {}

    static void clear() {
      for( SType s : TYPE2INITPART.keySet() )
        s.free();
      TYPE2INITPART.clear();
    }

    // Static hash
    private Type _t;          // A prototype Type, only looking at the static properties
    @Override public int hashCode() { return _t.static_hash(); }
    @SuppressWarnings("unchecked")
    @Override public boolean equals(Object o) {
      if( this==o ) return true;
      if( !(o instanceof SType) ) return false;
      return _t.static_eq(((SType)o)._t);
    }
  }

  // Worklist
  Work<Partition> WORK = new Work<>();

  // Def-Use edges.  Requires def-use edges which are not part of
  // the normal types; requires a side-structure build in a pre-pass.
  // Will be iterating over all (use,edge) pairs from a def.
  class DefUse {
    static private final NonBlockingHashMapLong<NonBlockingHashMap<String,Ary<Type>>> EDGES = new NonBlockingHashMapLong<>();
    @SuppressWarnings("unchecked")
    static private final Ary<NonBlockingHashMap<String,Ary<Type>>> FREES = new Ary(new NonBlockingHashMap[1],0);
    @SuppressWarnings("unchecked")
    static private final Ary<Ary<Type>> FREES0 = new Ary(new Ary[1],0);

    // use[edge]-->>def;
    static Type add_def_use( Type use, String edge, Type def ) {
      var edges = EDGES.get(def._uid);
      if( edges==null )
        EDGES.put(def._uid,edges = malloc());
      var uses = edges.get(edge);
      if( uses==null ) edges.put(edge,uses = malloc0());
      uses.push(use);
      return null;
    }

    // Get an iterator for all the uses of a def with edge e
    static Ary<Type> uses( String e, Type def ) {
      var edges = EDGES.get(def._uid);
      return edges==null ? null : edges.get(e);
    }

    // Get the set of edge labels leading to a def
    static Set<String> edges( Type def ) {
      var edges = EDGES.get(def._uid);
      return edges==null ? null : edges.keySet();
    }

    static private NonBlockingHashMap<String,Ary<Type>> malloc() {
      return FREES.isEmpty() ? new NonBlockingHashMap<>() : FREES.pop();
    }
    static private Ary<Type> malloc0() {
      return FREES0.isEmpty() ? new Ary<>(Type.class) : FREES0.pop();
    }

    // Free all use/def edge sets
    static void clear() {
      for( var edges : EDGES.values() ) {
        for( var uses : edges.values() )
          FREES0.push(uses).clear();
        FREES.push(edges).clear();
      }
      EDGES.clear();
    }
  }


  @SuppressWarnings("unchecked")
  private static <T extends Type> T _dfa_min(T nt) {
    // Walk the reachable set and all forward edges, building a reverse-edge set.

    /*
    BUG:
- an old cycle exists
- a new cycle with multi-entries to the old cycle, plus a new cycle is made.
- to be found equal, both the old and new cycles need to be in the same DFA MIN.

- NOT BUG: an old prefix line to an old cycle exists; a new prefix line to a new cycle is made.
- the standard cycle-equals-hash finds the complete new cycle


    */

    for( Type t : REACHABLE )  {
      if( t._hash!=0 && !t.interned() )
        t._hash=0; // Invariant: not-interned has no hash
      ((Cyclic)t).walk1( (t2,label) -> DefUse.add_def_use(t,label,t2) );
    }

    // Pick initial Partitions for every reachable Type
    for( Type t : REACHABLE )
      SType.init_part(t).add(t);

    // Put all partitions on worklist
    for( Partition P : Partition.PARTS )
      WORK.add(P);

    // Repeat until empty
    while( !WORK.isEmpty() )
      WORK.pop().do_split();

    // Walk through the Partitions, picking a head and mapping all edges from
    // head to head.
    for( Partition P : Partition.PARTS )
      ((Cyclic)P.head()).walk_update(Partition::head);

    // Edges are fixed, compute hash
    for( Partition P : Partition.PARTS ) if( P.head() instanceof TypeStruct ) P.head().set_hash();
    for( Partition P : Partition.PARTS ) if( P.head() instanceof TypeMemPtr ) P.head().set_hash();
    for( Partition P : Partition.PARTS ) if( P.head() instanceof TypeFunPtr ) P.head().set_hash();
    for( Partition P : Partition.PARTS )                                      P.head().set_hash();

    // Anything we make here might already be interned, at either the top-level
    // or at any intermediate point (and we might have been passed new types
    // with prior interned matches).  Replace any already interned parts.
    boolean done=false;
    while( !done ) {
      done = true;
      for( Partition P : Partition.PARTS ) {
        Type t = P.head();
        ((Cyclic)t).walk_update(Partition::head);
        Type i = t.intern_lookup();
        if( i!=null && t!=i )
          { done=false; P.set_head(i); }
      }
    }

    // Return the input types Partition head
    T rez = (T)Partition.TYPE2PART.get(nt._uid).head();
    Partition.clear();
    SType.clear();
    DefUse.clear();
    assert WORK.isEmpty();
    return rez;
  }

}
