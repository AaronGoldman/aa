package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.ErrMsg;
import com.cliffc.aa.Parse;
import com.cliffc.aa.tvar.TV3;
import com.cliffc.aa.type.*;

import static com.cliffc.aa.AA.unimpl;

// Store a value into a named struct field.  Does it's own nil-check and value
// testing; also checks final field updates.
public class StoreNode extends Node {
  private final Parse _bad;
  public StoreNode( Node mem, Node adr, Node val, Parse bad ) {
    super(OP_STORE,null,mem,adr,val);
    _bad = bad;    // Tests can pass a null, but nobody else does
  }
  private StoreNode( StoreNode st, Node mem, Node adr ) { this(mem,adr,st.rez(),st._bad); }

  @Override public boolean is_mem() { return true; }

  Node mem() { return in(1); }
  Node adr() { return in(2); }
  Node rez() { return in(3); }

  @Override public Type value() {
    Node mem = mem(), adr = adr(), rez = rez();
    Type tmem = mem._val;
    Type tadr = adr._val;
    Type tval = rez._val;  // Value
    if( !(tmem instanceof TypeMem    tm ) ) return tmem .oob(TypeMem.ALLMEM);
    if( !(tadr instanceof TypeMemPtr tmp) ) return tadr .oob(TypeMem.ALLMEM);
    TypeStruct tvs = tval instanceof TypeStruct ? (TypeStruct)tval : tval.oob(TypeStruct.ISUSED);

    Node str = LoadNode.find_previous_struct(mem, adr, tmp._aliases, false );
    boolean precise = adr.in(0) instanceof NewNode nnn && (nnn.rec()==str); // Precise is replace, imprecise is MEET
    return tm.update(tmp._aliases,tvs,precise);
  }
  // For most memory-producing Nodes, exactly 1 memory producer follows.
  private Node get_mem_writer() {
    for( Node use : _uses ) if( use.is_mem() ) return use;
    return null;
  }

  // Compute the liveness local contribution to def's liveness.  Turns around
  // value into live: if values are ANY then nothing is demand-able.
  @Override public Type live_use( Node def ) {
    if( _live== Type.ANY ) return Type.ANY;
    if( _live== Type.ALL ) return Type.ALL;
    Type mval = mem()._val;
    Type aval = adr()._val;
    if( mval == Type.ANY ) return Type.ANY;
    if( aval == Type.ANY ) return Type.ANY;
    
    TypeMem tlive = _live==Type.ALL ? TypeMem.ALLMEM    : (TypeMem   )_live; // Assert _live is ANY, ALL or a TypeMem
    TypeMem tmem  = mval ==Type.ALL ? TypeMem.ALLMEM    : (TypeMem   ) mval; // Assert  mval is ANY, ALL or a TypeMem
    TypeMemPtr tmp= aval ==Type.ALL ? TypeMemPtr.ISUSED : (TypeMemPtr) aval; // Assert  aval is ANY, ALL or a TypeMemPtr
    tmem = tmem.widen_mut_fields();
    TypeMem jmem  = (TypeMem)tlive.join(tmem);
    // TODO: if aval is precise alias, can remove it also from jmem
    if( def==mem() ) return jmem;
    // Available (live) struct
    TypeStruct ts = jmem.ld(tmp);
    if( def==adr() ) return ts.oob(); // Live-use for the adr(), which is a Type.ANY/ALL
    return ts;                  // Live-use for the rez() which is a TypeStruct liveness    
  }

  @Override public Node ideal_reduce() {
    if( _live == Type.ANY ) return null; // Dead from below; nothing fancy just await removal
    Node mem = mem();
    Node adr = adr();
    Type ta = adr._val;
    TypeMemPtr tmp = ta instanceof TypeMemPtr ? (TypeMemPtr)ta : null;

    // Is this Store dead from below?
    if( mem==this ) return null; // Dead self-cycle
    if( ta.above_center() ) return mem; // All memory is high, so dead
    if( tmp!=null && mem._val instanceof TypeMem ) {
      TypeStruct ts0 = (_live instanceof TypeMem tm ? tm : _live.oob(TypeMem.ALLMEM)).ld(tmp);
      if( ts0.above_center() )  // Dead from below
        return mem;
    }

    // No need for 'Fresh' address, as Stores have no TVar (produce memory not a scalar)
    if( adr() instanceof FreshNode fsh )
      return set_def(2,fsh.id());

    // Escape a dead MemSplit
    if( mem instanceof MProjNode && mem.in(0) instanceof MemSplitNode msp &&
        msp.join()==null ) {
      set_def(1,msp.mem());
      xval();                   // Update memory state to include all the default memory
      return this;
    }

    //// If Store is into a value New, fold into the New.
    //// Happens inside value constructors.
    //if( adr instanceof NewNode nnn && nnn._is_val && _fold(nnn) )
    //  return mem;

    // Store into a NewNode, same memory and address
    if( mem instanceof MProjNode && adr instanceof ProjNode && mem.in(0) == adr.in(0) && mem.in(0) instanceof NewNode nnn &&
        // Do not bypass a parallel writer
        mem.check_solo_mem_writer(this) &&
        // And liveness aligns
        _live.isa(mem._live) ) {
      StructNode st = _fold(rez());
      Env.GVN.revalive(st,mem.in(0),mem);
      return st==null ? null : mem;
    }
    //
    //// If Store is of a MemJoin and it can enter the split region, do so.
    //// Requires no other memory *reader* (or writer), as the reader will
    //// now see the Store effects as part of the Join.
    //if( tmp != null && mem instanceof MemJoinNode && mem._uses._len==1 ) {
    //  Node memw = _uses._len==0 ? this : get_mem_writer(); // Zero or 1 mem-writer
    //  // Check the address does not have a memory dependence on the Join.
    //  // TODO: This is super conservative
    //  if( adr instanceof FreshNode ) adr = ((FreshNode)adr).id();
    //  if( memw != null && adr instanceof ProjNode && adr.in(0) instanceof NewNode )
    //    return ((MemJoinNode)mem).add_alias_below_new(new StoreNode(this,mem,adr()),this);
    //}
    //
    return null;
  }

  // Recursively collapse a set of SetFields into a single-use StructNode
  static StructNode _fold(Node rez) {
    if( rez instanceof StructNode st ) return st;
    SetFieldNode sfn = (SetFieldNode)rez;
    StructNode st = _fold(sfn.in(0));
    //if( st==null || !st.set_fld(sfn._fld,sfn._fin,sfn.in(1),false) )
    //  return null;
    //return st;
    throw unimpl();
  }


  @Override public Node ideal_grow() {
    Node mem = mem();
    Node adr = adr();

    // If Store is of a memory-writer, and the aliases do not overlap, make parallel with a Join
    if( adr._val instanceof TypeMemPtr tmp &&
        mem.is_mem() && mem.check_solo_mem_writer(this) ) {
      Node head2=null;
      if( mem instanceof StoreNode ) head2=mem;
      else if( mem instanceof MProjNode ) {
        if( mem.in(0) instanceof CallEpiNode cepi ) head2 = cepi.call();
        else if( mem.in(0) instanceof NewNode nnn ) head2 = nnn;
      }
      // Check no extra readers/writers at the split point
      if( head2 != null ) {
        // && MemSplitNode.check_split(this,escapees(),mem) ) {
      //  MemSplitNode.insert_split(this, escapees(), this, mem, head2);
      //  assert _uses._len==1 && _uses.at(0) instanceof MemJoinNode;
      //  return _uses.at(0); // Return the mem join
        return null;  // TODO: Turn back on
      }
    }
    return null;
  }

  @Override public ErrMsg err( boolean fast ) {
    Type tadr = adr()._val;
    Type tmem = mem()._val;
    if( tadr.above_center() ) return null;
    if( tmem.above_center() ) return null;
    if( !(tadr instanceof TypeMemPtr ptr) )
      return bad("Unknown",fast,null); // Not a pointer nor memory, cannot store a field
    if( !(tmem instanceof TypeMem) ) return bad("Unknown",fast,null);
    if( ptr.must_nil() ) return fast ? ErrMsg.FAST : ErrMsg.niladr(_bad,"Struct might be nil when writing",null);
    return null;
  }
  private ErrMsg bad( String msg, boolean fast, TypeStruct to ) {
    if( fast ) return ErrMsg.FAST;
    //boolean is_closure = adr() instanceof NewNode nnn && nnn._is_closure;
    //return ErrMsg.field(_bad,msg,_fld,is_closure,to);
    throw unimpl();
  }

  @Override public boolean has_tvar() { return false; }

  @Override public boolean unify( boolean test ) {
    TV3 ptr = adr().tvar();     // Should be leaf, nilable, or ptr
    TV3 rez = rez().tvar();     // Should be leaf, or struct
    //assert !ptr.is_obj() && !rez.is_nil() && rez.arg("*")==null;
    //if( ptr.is_nil() ) throw unimpl();
    //TV3 obj = ptr.arg("*");
    //if( obj!=null )
    //  return obj.unify(rez,test);
    //ptr.add_fld("*",rez);
    //return true;
    throw unimpl();
  }

  @Override public void add_work_hm() {
    Env.GVN.add_flow(adr());
    Env.GVN.add_flow(rez());
  }

}
