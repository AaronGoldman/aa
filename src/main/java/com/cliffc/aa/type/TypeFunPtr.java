package com.cliffc.aa.type;

import com.cliffc.aa.node.FunNode;
import com.cliffc.aa.util.SB;
import com.cliffc.aa.util.VBitSet;
import java.util.BitSet;

// Function indices or function pointers; a single instance can include all
// possible aliased function pointers.  Function pointers can be executed, are
// not GC'd, and cannot be Loaded or Stored through (although they can be
// loaded & stored).  Each function index (or fidx) is a constant value, a
// classic code pointer.  Cloning the code immediately also clones the fidx
// with a new fidx bit for the new cloned copy.
//
// The formal function signature is included.  This is the type of EpilogNodes,
// and is included in FunNodes (FunNodes type is same as a RegionNode; simply
// Control or not).

// CallNodes use this type to check their incoming arguments.  CallNodes get
// their return values (including memory) from the Epilog itself, not the
// FunNode and not this type.


// CNC!!!!  TFP represents code-pointers, and can be constants.  The signature
// is not needed.... can be built from the union of FunNode sigs from the
// fidxs.  Perhaps: allow TFP to be constants but the dual does not flip
// signature bits.  Handle meet-vs-join of code-pointers by meet/join sigs from
// FunNode.  Doesn't work...
// Plan B: no sig in TFP, just a collection of code-pointer bits.
//         FunNode has args & ret seperately.  Epilog might also.
//         Type of Epilog is a Code-pointer (a TFP no sig).
//         CallNodes need to get arg types from the fidx->FunNode path.
// Probably unwind most changes, get back to parse1-6 working.
//
public final class TypeFunPtr extends Type<TypeFunPtr> {
  // List of known functions in set, or 'flip' for choice-of-functions.
  private BitsFun _fidxs;       // Known function bits
  // Union (or join) signature of said functions; depends on if _fidxs is
  // above_center() or not.
  public TypeStruct _args;      // Standard args, zero-based, no memory
  public Type _ret;             // Standard formal return type.

  private TypeFunPtr(BitsFun fidxs, TypeStruct args, Type ret ) { super(TFUNPTR); init(fidxs,args,ret); }
  private void init (BitsFun fidxs, TypeStruct args, Type ret ) { _fidxs = fidxs; _args=args; _ret=ret; }
  @Override int compute_hash() { return TFUNPTR + _fidxs._hash + _args._hash + _ret._hash; }
  @Override public boolean equals( Object o ) {
    if( this==o ) return true;
    if( !(o instanceof TypeFunPtr) ) return false;
    TypeFunPtr tf = (TypeFunPtr)o;
    return _fidxs==tf._fidxs && _args==tf._args && _ret==tf._ret;
  }
  // Never part of a cycle, so the normal check works
  @Override public boolean cycle_equals( Type o ) { return equals(o); }
  @Override public String str( VBitSet dups) {
    return "*"+names()+":{"+_args.str(dups)+"-> "+_ret.str(dups)+"}";}
  public String names() { return FunNode.names(_fidxs,new SB()).toString(); }

  private static TypeFunPtr FREE=null;
  @Override protected TypeFunPtr free( TypeFunPtr ret ) { FREE=this; return ret; }
  public static TypeFunPtr make( BitsFun fidxs, TypeStruct args, Type ret ) {
    TypeFunPtr t1 = FREE;
    if( t1 == null ) t1 = new TypeFunPtr(fidxs,args,ret);
    else {   FREE = null;        t1.init(fidxs,args,ret); }
    TypeFunPtr t2 = (TypeFunPtr)t1.hashcons();
    return t1==t2 ? t1 : t1.free(t2);
  }
  // Used by testing only
  public static TypeFunPtr make_new(TypeStruct args, Type ret) { return make(BitsFun.make_new_fidx(BitsFun.ALL),args,ret); }
  public TypeFunPtr make_fidx( int fidx ) { return make(BitsFun.make0(fidx),_args,_ret); }
  public TypeFunPtr make_new_fidx( int parent, TypeStruct args ) { return make(BitsFun.make_new_fidx(parent),args,_ret); }
  public static TypeFunPtr make_anon() { return make_new(TypeStruct.ALLSTRUCT,Type.SCALAR); } // Make a new anonymous function ptr

  public  static final TypeFunPtr GENERIC_FUNPTR = make(BitsFun.NZERO,TypeStruct.ALLSTRUCT,Type.SCALAR);
  private static final TypeFunPtr TEST_INEG = make(BitsFun.make0(2),TypeStruct.INT64,TypeInt.INT64);
  public  static final TypeFunPtr EMPTY = make(BitsFun.EMPTY,TypeStruct.ALLSTRUCT,Type.XSCALAR);
  static final TypeFunPtr[] TYPES = new TypeFunPtr[]{GENERIC_FUNPTR,TEST_INEG};

  @Override protected TypeFunPtr xdual() { return new TypeFunPtr(_fidxs.dual(),_args.dual(),_ret.dual()); }
  @Override protected Type xmeet( Type t ) {
    switch( t._type ) {
    case TFUNPTR:break;
    case TFLT:
    case TINT:
    case TMEMPTR:
    case TRPC:   return cross_nil(t);
    case TNIL:
    case TFUN:
    case TTUPLE:
    case TOBJ:
    case TSTR:
    case TSTRUCT:
    case TMEM:   return ALL;
    default: throw typerr(t);   // All else should not happen
    }
    TypeFunPtr tf = (TypeFunPtr)t;
    // Function args are MEET during MEET
    return make(_fidxs.meet(tf._fidxs),(TypeStruct)_args.meet(tf._args),_ret.meet(tf._ret));
  }

  public BitsFun fidxs() { return _fidxs; }
  public int fidx() { return _fidxs.getbit(); } // Asserts internally single-bit
  public Type arg(int idx) { return _args.at(idx); }
  public boolean is_class() { return _fidxs.is_class(); }

  @Override public BitsAlias recursive_aliases(BitsAlias abs, TypeMem mem) {
    if( _fidxs.test(1) ) return BitsAlias.NZERO; // All functions, all closures
    if( _fidxs.above_center() ) return abs;      // Choosing functions with no aliases
    BitSet bs = _fidxs.tree().plus_kids(_fidxs);
    for( int fidx = bs.nextSetBit(1); fidx >= 0; fidx = bs.nextSetBit(fidx+1) ) {
      BitsAlias cls = FunNode.find_fidx(fidx)._closure_aliases;
      for( int alias : cls )
        abs = mem.recursive_aliases(abs,alias);
      if( abs.test(1) ) return abs; // Shortcut for already being full
    }
    return abs;
  }

  // Function args below center when the TFP is above center.
  @Override public boolean above_center() { return _args.above_center(); }
  @Override public boolean may_be_con()   { return above_center() || is_con(); }
  @Override public boolean is_con()       { return _fidxs.abit() != -1 && !is_class(); }
  @Override public boolean must_nil() { return _fidxs.test(0) && !above_center(); }
  @Override public boolean may_nil() { return _fidxs.may_nil(); }
  @Override Type not_nil() {
    BitsFun bits = _fidxs.not_nil();
    return bits==_fidxs ? this : make(bits,_args,_ret);
  }
  @Override public Type meet_nil() {
    if( _fidxs.test(0) )      // Already has a nil?
      return _fidxs.above_center() ? NIL : this;
    return make(_fidxs.meet(BitsFun.NIL),_args,_ret);
  }
  // Keep the high parts
  @Override public Type startype() {
    BitsFun fidxs  = _fidxs.above_center() ? _fidxs : _fidxs.dual();
    TypeStruct args= _args.startype();
    Type ret       = _ret .startype();
    return make(fidxs,args,ret);
  }

  // Generic functions
  public boolean is_forward_ref() {
    if( _fidxs.abit() == -1 ) return false; // Multiple fidxs
    if( _fidxs.getbit() == 1 ) return false; // Thats the generic function ptr
    FunNode fun = FunNode.find_fidx(fidx());
    return fun != null && fun.is_forward_ref();
  }
}
