package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.tvar.TV2;
import com.cliffc.aa.util.Util;
import org.jetbrains.annotations.NotNull;

import static com.cliffc.aa.AA.*;

// Load a named field from a struct.  Does it's own nil-check testing.  Loaded
// value depends on the struct typing.
public class LoadNode extends Node {
  private final String _fld;    // Field being loaded
  private final Parse _bad;

  public LoadNode( Node mem, Node adr, String fld, Parse bad ) {
    super(OP_LOAD,null,mem,null,adr);
    _fld = fld;
    _bad = bad;
  }
  @Override public String xstr() { return "."+_fld; }   // Self short name
  String  str() { return xstr(); } // Inline short name
  Node mem() { return in(MEM_IDX); }
  Node adr() { return in(ARG_IDX); }
  private Node set_mem(Node a) { return set_def(MEM_IDX,a); }
  public TypeFld find(TypeStruct ts) { return ts.get(_fld); }

  // Strictly reducing optimizations
  @Override public Node ideal_reduce() {
    Node adr = adr();
    Type tadr = adr._val;
    if( !(tadr instanceof TypeMemPtr tmp) ) return null;
    // Loads from value types do not need the memory edge
    if( tmp.is_valtype() && mem()!=null )
      return set_def(MEM_IDX,null);

    // See if this is a named object as a ptr, with a prototype.
    // Can be either a reference or value type.
    String tname = ValFunNode.valtype(tmp);
    if( tname!=null ) {
      NewNode nnn = Env.PROTOS.get(tname); // Get the prototype
      if( nnn!=null ) {
        // Is either a reference or a value type
        TypeFld fld = nnn.fld(_fld);
        if( fld==null ) return null; // No such field in prototype
        // For both, eager final fields are moved to the prototype.
        if( fld._access==TypeFld.Access.Final ) { // Final fields: might be in the prototype
          // Load from the prototype
          Node p = nnn.in(fld._order);
          if( p._val==Type.ALL ) return null;
          // Instance call; move the adr into Unresolved/FunPtr
          if( p._val instanceof TypeFunPtr tfp ) {
            if( tfp.dsp()==TypeMemPtr.NO_DISP ) return p; // No display ("static" prototype call)
            if( p instanceof UnresolvedNode ) return ((UnresolvedNode)p).bind(adr());
            assert p instanceof FunPtrNode; // clone, inject adr() as display
            return p.copy(true).set_def(1,adr());
          }
          // Other prototype constants
          throw unimpl();
          //return nnn.in(fld._order);
        }
        // fetch directly from local
        if( adr instanceof ValNode val )
          return val.in(Util.find(val._flds,_fld));
      }
    }

    // If we can find an exact previous store, fold immediately to the value.
    Node st = find_previous_store(mem(),adr,tmp._aliases,_fld,true);
    if( st!=null ) {
      Node rez = st instanceof StoreNode
        ? ((StoreNode)st).rez()
        : ((  NewNode)st).get(_fld);
      return rez==this ? null : rez;
    }

    return null;
  }

  // Changing edges to bypass, but typically not removing nodes nor edges
  @Override public Node ideal_mono() {
    Node mem = mem();
    // Bypass unrelated Stores, but only if the Address predates the Store.  If
    // the Load address depends on the Store memory, then the Load cannot
    // bypass the Store.
    if( mem instanceof StoreNode ) {
      StoreNode st2 = (StoreNode)mem;
      if( st2.adr()==adr() && !Util.eq(st2._fld,_fld) ) // Very weak "Address must predate" test
        return set_mem(st2.mem());
    }

    Node adr = adr();
    Type tadr = adr._val;
    BitsAlias aliases = tadr instanceof TypeMemPtr ? ((TypeMemPtr)tadr)._aliases : null;

    // Load can move past a Join if all aliases align.
    if( mem instanceof MemJoinNode && aliases != null ) {
      Node jmem = ((MemJoinNode)mem).can_bypass(aliases);
      if( jmem != null ) {
        jmem.xval();
        return set_mem(jmem);
      }
    }

    // Load can move out of a Call, if the function has no Parm:mem - happens
    // for single target calls that do not (have not yet) inlined.
    if( mem instanceof MProjNode && mem.in(0) instanceof CallNode )
      return set_mem(((CallNode)mem.in(0)).mem());

    // Load can bypass a New or Store if the address does not depend on the New/St.
    if( aliases != null && mem instanceof MrgProjNode ) {
      NewNode nnn = ((MrgProjNode)mem).nnn();
      // Bypass if aliases do not overlap
      if( !aliases.test_recur(nnn._alias) )
        return set_mem(mem.in(1));
      // Also bypass if address predates the allocation.  Here we just see that
      // the address comes from the function Parm, and the New is made in the
      // function.
      Node adr2 = adr instanceof CastNode ? adr.in(1) : adr;
      if( adr2 instanceof ParmNode )
        return set_mem(mem.in(1));
    }

    return null;
  }

  @Override public Node ideal_grow() {
    Node mem = mem();
    Node adr = adr();
    // Load from a memory Phi; split through in an effort to sharpen the memory.
    // TODO: Only split thru function args if no unknown_callers, and must make a Parm not a Phi
    // TODO: Hoist out of loops.
    if( mem!=null && mem._op == OP_PHI && adr.in(0) instanceof NewNode ) {
      Node lphi = new PhiNode(Type.SCALAR,((PhiNode)mem)._badgc,mem.in(0));
      for( int i=1; i<mem._defs._len; i++ )
        lphi.add_def(Env.GVN.add_work_new(new LoadNode(mem.in(i),adr,_fld,_bad)));
      return lphi;
    }

    return null;
  }

  // Find a matching prior Store or NewObj - matching field name and address.
  // Returns null if highest available memory does not match name & address.
  static Node find_previous_store(Node mem, Node adr, BitsAlias aliases, String fld, boolean is_load ) {
    if( mem==null ) return null;
    Type tmem = mem._val;
    if( !(tmem instanceof TypeMem) || aliases==null ) return null;
    // Walk up the memory chain looking for an exact matching Store or New
    int cnt=0;
    while(true) {
      cnt++; assert cnt < 100; // Infinite loop?
      if( mem instanceof StoreNode ) {
        StoreNode st = (StoreNode)mem;
        if( Util.eq(st._fld,fld) ) {
          if( st.adr()==adr ) return st.err(true)== null ? st : null; // Exact matching store
          // Matching field, wrong address.  Look for no-overlap in aliases
          Type tst = st.adr()._val;
          if( !(tst instanceof TypeMemPtr) ) return null; // Store has weird address
          BitsAlias st_alias = ((TypeMemPtr)tst)._aliases;
          if( aliases.join(st_alias) != BitsAlias.EMPTY )
            return null;        // Aliases not disjoint, might overlap but wrong address
        }               // Wrong field name, cannot match
        if( mem == st.mem() ) return null;
        mem = st.mem(); // Advance past

      //} else if( mem instanceof MemPrimNode.LValueWrite ) {
      //  // Array stores and field loads never alias
      //  mem = ((MemPrimNode)mem).mem();

      } else if( mem instanceof MProjNode ) {
        Node mem0 = mem.in(0);
        if( mem0 instanceof CallEpiNode ) { // Bypass an entire function call
          if( ((CallEpiNode)mem0)._is_copy ) return null;
          Type tmem0 = mem._val;
          Type tmem1 = ((CallEpiNode)mem0).call().mem()._val;
          if( !(tmem0 instanceof TypeMem) || !(tmem1 instanceof TypeMem) ) return null;
          mem = _find_previous_store_call(aliases,(TypeMem)tmem0,(TypeMem)tmem1,(CallEpiNode)mem0,fld,is_load);
          if( mem==null ) return null;
        } else if( mem0 instanceof MemSplitNode ) { // Lifting out of a split/join region
          mem = ((MemSplitNode)mem0).mem();
        } else if( mem0 instanceof CallNode ) { // Lifting out of a Call
          mem = ((CallNode)mem0).mem();
        } else {
          throw unimpl(); // decide cannot be equal, and advance, or maybe-equal and return null
        }
      } else if( mem instanceof MrgProjNode ) {
        MrgProjNode mrg = (MrgProjNode)mem;
        NewNode nnn = mrg.nnn();
        TypeFld tfld = nnn._ts.get(fld);
        if( tfld!=null && adr == nnn ) return nnn; // Direct hit
        // wrong field name or wrong alias, cannot match
        if( aliases.test_recur(nnn._alias) ) return null; // Overlapping, but wrong address - dunno, so must fail
        mem = mrg.mem(); // Advance past
      } else if( mem instanceof MemJoinNode ) {
        Node jmem = ((MemJoinNode)mem).can_bypass(aliases);
        if( jmem == null ) return null;
        mem = jmem;
      } else if( mem instanceof ParmNode ) {
        if( mem.in(0) instanceof FunNode && mem.in(0).is_copy(1)!=null ) mem = mem.in(1); // FunNode is dying, copy, so ParmNode is also
        else return null;

      } else if( mem instanceof PhiNode || // Would have to match on both sides, and Phi the results
                 mem instanceof StartMemNode ||
                 mem instanceof ConNode) {
        return null;
      } else {
        throw unimpl(); // decide cannot be equal, and advance, or maybe-equal and return null
      }
    }
  }

  // Can bypass call?  Return null if cannot or call.mem if can.
  static private Node _find_previous_store_call( BitsAlias aliases, TypeMem tmem0, TypeMem tmem1, CallEpiNode cepi, String fld, boolean is_load ) {
    // TODO: Strengthen this.  Global no-esc can bypass, IF during inline/clone
    // each clone body updates both aliases everywhere.
    if( !is_load ) return null; // For now, Store types NEVER bypass a call.
    CallNode call = cepi.call();
    if( tmem0.fld_not_mod(aliases, fld) && tmem1.fld_not_mod(aliases, fld) )
      return call.mem(); // Loads from final memory can bypass calls.  Stores cannot, store-over-final is in error.
    //TypeMemPtr escs = CallNode.tesc(call._val);
    //if( escs._aliases.join(aliases)==BitsAlias.EMPTY )
    //  return call.mem(); // Load from call; if memory is made *in* the call this will fail later on an address mismatch.
    return null;         // Stuck behind call
  }


  @Override public Type value() {
    Node adr = adr();
    Type tadr = adr._val;
    if( !(tadr instanceof TypeMemPtr tmp) ) return tadr.oob();
    // Loading from a Value type?
    if( ValFunNode.valtype(tmp)!=null ) {
      if( !(tmp._obj instanceof TypeStruct) ) return tmp._obj.oob(Type.SCALAR);
      TypeFld fld = tmp._obj.get(_fld);
      return fld==null ? Type.SCALAR : fld._t; // Check no-such-field
    }

    // Loading from TypeMem - will get a TypeObj out.
    Node mem = mem();
    Type tmem = mem._val;       // Memory
    if( !(tmem instanceof TypeMem) ) return tmem.oob(); // Nothing sane
    TypeStruct tobj = ((TypeMem)tmem).ld(tmp);
    return get_fld(tobj);
  }

  // Load the value
  private @NotNull Type get_fld( TypeStruct ts ) {
    TypeFld fld = ts.get(_fld);  // Find the named field
    if( fld==null ) return ts.oob();
    return fld._t;          // Field type
  }

  @Override public void add_flow_use_extra(Node chg) {
    if( chg==adr() ) { Env.GVN.add_flow(mem()); Env.GVN.add_reduce(this); } // Address into a Load changes, the Memory can be more alive, or this not in Error
    if( chg==mem() ) Env.GVN.add_flow(mem());  // Memory value lifts to ANY, memory live lifts also.
    if( chg==mem() ) Env.GVN.add_flow(adr());  // Memory value lifts to an alias, address is more alive
    // Memory improves, perhaps Load can bypass Call
    if( chg==mem() && mem().in(0) instanceof CallEpiNode ) Env.GVN.add_reduce(this);
    // Memory becomes a MrgProj, maybe Load can bypass MrgProj
    if( chg==mem() && chg instanceof MrgProjNode ) Env.GVN.add_mono(this);
  }

  // The only memory required here is what is needed to support the Load.
  // If the Load is alive, so is the address.

  // If the Load computes a constant, the address live-ness is determined how
  // Combo deals with constants, and not here.
  @Override public TypeMem live_use(Node def ) {
    if( def==adr() ) return TypeMem.ALIVE;
    Type tmem = mem()._val;
    Type tptr = adr()._val;
    if( !(tmem instanceof TypeMem   ) ) return tmem.oob(TypeMem.ALLMEM); // Not a memory?
    if( !(tptr instanceof TypeMemPtr) ) return tptr.oob(TypeMem.ALLMEM); // Not a pointer?
    if( tptr.above_center() ) return TypeMem.ANYMEM; // Loaded from nothing
    // Only named the named field from the named aliases is live.
    TypeStruct ldef = _live==TypeMem.LNO_DISP ? TypeStruct.LNO_DISP : TypeStruct.ALIVE;
    return ((TypeMem)tmem).remove_no_escapes(((TypeMemPtr)tptr)._aliases,_fld, ldef);
  }

  // Standard memory unification; the Load unifies with the loaded field.
  @Override public boolean unify( boolean test ) {
    TV2 self = tvar();
    TV2 rec = adr().tvar();
    rec.push_dep(this);

    TV2 fld = rec.arg(_fld);
    if( fld!=null )           // Unify against a pre-existing field
      return fld.unify(self, test);

    // Add struct-ness if possible
    if( !rec.is_obj() && !rec.is_nil() )
      rec.make_open_struct();
    // Add the field
    if( rec.is_obj() && rec.is_open() ) {
      rec.add_fld(_fld,self);
      return true;
    }
    // Closed/non-record, field is missing
    if( self._err!=null ) return false;
    self._err = "Missing field "+_fld;
    return true;
  }
  public void add_work_hm() {
    super.add_work_hm();
    Env.GVN.add_flow(adr());
  }

  @Override public ErrMsg err( boolean fast ) {
    Type tadr = adr()._val;
    if( tadr.must_nil() ) return fast ? ErrMsg.FAST : ErrMsg.niladr(_bad,"Struct might be nil when reading",_fld);
    if( tadr==Type.ANY ) return null; // No error, since might fall to any valid thing
    if( !(tadr instanceof TypeMemPtr) )
      return bad(fast,null); // Not a pointer nor memory, cannot load a field
    TypeMemPtr ptr = (TypeMemPtr)tadr;
    if( ValFunNode.valtype(tadr)!=null ) // These should always fold
      return bad(fast,ptr._obj);
    Type tmem = mem()._val;
    if( tmem==Type.ALL ) return bad(fast,null);
    if( tmem==Type.ANY ) return null; // No error
    TypeStruct objs = tmem instanceof TypeMem
      ? ((TypeMem   )tmem).ld(ptr) // General load from memory
      : ((TypeStruct)tmem);
    if( objs==TypeStruct.UNUSED ) return null; // No error, since might fall to anything
    // Both type systems know about the field
    if( objs.get(_fld)==null )
      return bad(fast,objs);
    return null;
  }
  private ErrMsg bad( boolean fast, TypeStruct to ) {
    boolean is_closure = adr() instanceof NewNode nnn && nnn._is_closure;
    return fast ? ErrMsg.FAST : ErrMsg.field(_bad,"Unknown",_fld,is_closure,to);
  }
  @Override public int hashCode() { return super.hashCode()+_fld.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    return (o instanceof LoadNode) && Util.eq(_fld,((LoadNode)o)._fld);
  }

}
