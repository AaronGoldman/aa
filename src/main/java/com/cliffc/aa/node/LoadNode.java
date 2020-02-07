package com.cliffc.aa.node;

import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Util;

// Load a named field from a struct.  Does it's own nil-check testing.  Loaded
// value depends on the struct typing.
public class LoadNode extends Node {
  private final String _fld;
  private final Parse _bad;
  public LoadNode( Node mem, Node adr, String fld, Parse bad ) {
    super(OP_LOAD,null,mem,adr);
    _fld = fld;
    _bad = bad;
  }
  String xstr() { return "."+_fld; }   // Self short name
  String  str() { return xstr(); } // Inline short name
  private Node mem() { return in(1); }
          Node adr() { return in(2); }
  private Node set_mem(Node a, GVNGCM gvn) { return set_def(1,a,gvn); }

  @Override public Node ideal(GVNGCM gvn, int level) {
    Node mem  = mem();
    Node addr = adr();

    Type tadr = gvn.type(addr);
    BitsAlias aliases = tadr instanceof TypeMemPtr ? ((TypeMemPtr)tadr)._aliases : null;
    int alias = aliases == null ? -2 : aliases.strip_nil().abit();

    // Load from a single alias bypasses a MemMerge
    if( alias >= 0 && mem instanceof MemMergeNode ) {
      // TODO: Actually if all bits subset a single entry, and no partial
      // subsets, can bypass along the single entry.
      // Find nearest alias parent
      Node obj = ((MemMergeNode)mem).alias2node_precise(alias);
      if( obj != null ) return set_mem(obj,gvn);
    }

    // Loads can bypass a call, if the return memory does not stomp the alias.
    if( aliases != null && mem instanceof MProjNode && mem.in(0) instanceof CallEpiNode ) {
      CallEpiNode cepi = (CallEpiNode)mem.in(0);
      if( !cepi.is_copy() ) {
        TypeMem retmem = (TypeMem)((TypeTuple)gvn.type(cepi)).at(3);
        if( retmem.is_clean(aliases,_fld) )
          return set_mem(cepi.call().mem(),gvn);
        if( alias > 0 && retmem.at(alias) == TypeObj.XOBJ )
          return set_mem(cepi.call().mem(),gvn);
      }
    }

    // Loads against a NewNode cannot NPE, cannot fail, always return the input
    NewObjNode nnn = addr.in(0) instanceof NewObjNode ? (NewObjNode)addr.in(0) : null;
    int idx=-1;
    if( nnn != null && nnn == mem.in(0) && (idx=nnn._ts.find(_fld)) != -1 )
      return nnn.fld(idx);      // Field value

    // Load from a memory Phi; split through in an effort to sharpen the memory.
    if( mem instanceof PhiNode && nnn!=null ) {
      // TODO: Only split thru function args if no unknown_callers, and must make a Parm not a Phi
      if( !(mem instanceof ParmNode) ) {
        Node lphi = new PhiNode(((PhiNode)mem)._badgc,mem.in(0));
        for( int i=1; i<mem._defs._len; i++ )
          lphi.add_def(gvn.xform(new LoadNode(mem.in(i),addr,_fld,_bad)));
        return lphi;
      }
    }

    // Loads against an equal store; cannot NPE since the Store did not.
    StoreNode st=null;
    if( mem instanceof StoreNode && addr == (st=((StoreNode)mem)).adr() ) {
      if( Util.eq(_fld,st._fld) && st.err(gvn)==null )
        return st.val();
    }

    // Bypass unrelated Stores
    if( st != null && st.err(gvn)==null &&
        !Util.eq(_fld,st._fld) )
      return set_mem(st.mem(),gvn);
    return null;
  }

  @Override public Type value(GVNGCM gvn) {
    Type adr = gvn.type(adr());
    if( adr.isa(TypeMemPtr.OOP0.dual()) ) return Type.XSCALAR;
    if( TypeMemPtr.OOP0.isa(adr) ) return Type.SCALAR; // Very low, might be any address
    if( adr.is_forward_ref() ) return Type.SCALAR;
    if( !(adr instanceof TypeMemPtr) )
      return adr.above_center() ? Type.XSCALAR : Type.SCALAR;
    TypeMemPtr tmp = (TypeMemPtr)adr;

    // Load can bypass a Call, if the memory is not returned from the call.
    // This optimization is specifically targeting simple recursive functions.
    Node mem = mem();
    if( mem instanceof MProjNode && mem.in(0) instanceof CallEpiNode ) {
      CallEpiNode cepi = (CallEpiNode)mem.in(0);
      if( !cepi.is_copy() ) {
        TypeMem retmem = (TypeMem)((TypeTuple)gvn.type(cepi)).at(3);
        if( retmem.is_clean(tmp.aliases(),_fld) )
          mem = cepi.call().mem();
        int alias = tmp.aliases().strip_nil().abit();
        if( alias > 0 && retmem.at(alias) == TypeObj.XOBJ )
          mem = cepi.call().mem();
      }
    }

    // Loading from TypeMem - will get a TypeObj out.
    Type tmem = gvn.type(mem); // Memory
    if( !(tmem instanceof TypeStruct) ) {
      if( !(tmem instanceof TypeMem) ) // Nothing sane
        return tmem.above_center() ? Type.XSCALAR : Type.SCALAR;
      TypeObj obj = ((TypeMem)tmem).ld(tmp);
      tmem = obj;
    }

    // Loading from TypeObj - hoping to get a field out
    if( tmem == TypeObj.XOBJ ) return Type.XSCALAR;
    if( tmem == TypeObj. OBJ ) return Type. SCALAR;
    // Struct; check for field
    if( tmem instanceof TypeStruct ) {
      TypeStruct ts = (TypeStruct)tmem;
      int idx = ts.find(_fld);  // Find the named field
      if( idx != -1 ) {         // Found a field
        Type t = ts.at(idx);
        if( tmp.must_nil() )    // Might be in-error, but might fall to correct
          return t.widen();     // Return conservative but sane answer
        return ts.at(idx);      // Field type
      }
      // No such field
    }
    return tmem.above_center() ? Type.XSCALAR : Type.SCALAR; // No loading from e.g. Strings
  }

  // Set of used aliases across all inputs (not StoreNode value, but yes address)
  @Override public BitsAlias alias_uses(GVNGCM gvn) {
    Type t = gvn.type(adr());
    if( !(t instanceof TypeMemPtr) ) return BitsAlias.NZERO; // Wait until memory settles out
    TypeMemPtr tmp = (TypeMemPtr)t;
    return tmp.aliases();
  }
  @Override public String err(GVNGCM gvn) {
    Type tadr = gvn.type(adr());
    if( tadr.must_nil() ) return bad("Struct might be nil when reading");
    if( !(tadr instanceof TypeMemPtr) )
      return bad("Unknown"); // Not a pointer nor memory, cannot load a field
    TypeMemPtr ptr = (TypeMemPtr)tadr;
    Type tmem = gvn.type(mem());
    TypeObj objs = tmem instanceof TypeMem
      ? ((TypeMem)tmem).ld(ptr) // General load from memory
      : ((TypeObj)tmem);
    if( !(objs instanceof TypeStruct) || ((TypeStruct)objs).find(_fld) == -1 )
      return bad("Unknown");
    return null;
  }
  private String bad( String msg ) {
    String f = msg+" field '."+_fld+"'";
    return _bad==null ? f : _bad.errMsg(f);
  }
  @Override public Type all_type() { return Type.SCALAR; }
  @Override public int hashCode() { return super.hashCode()+_fld.hashCode(); }
  @Override public boolean equals(Object o) {
    if( this==o ) return true;
    if( !super.equals(o) ) return false;
    return (o instanceof LoadNode) && Util.eq(_fld,((LoadNode)o)._fld);
  }
}
