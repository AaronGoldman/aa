package com.cliffc.aa.type;

import com.cliffc.aa.AA;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;

/** an implementation of language AA
 */

// C2-style type system, with Meet & Dual.  Symmetric around the centerline of
// constants.  Fixed height, so a finite count of Meet stablizes; a unique All
// (Bottom; no known value) and due to symmetry a unique Any (Top, all values
// simultaneously).  Support function types, various kinds of numeric ranges,
// null and a Object Oriented single-inheritance hierarchy.
//
// During program typing, always keeping the "loosest" possible program and if
// this program still types as 'Any' then the program is ambiguious.  'All'
// represents a type conflict.
//
// ASCII-Grams of Type Lattices
//
// Ints:   ~int64 -> ~int32 -> ~int16 -> ~int8 -> ~int1 -> {...-3,-2,-1,0,1,2,3,...} -> int1 -> int8 -> int16 -> int32 -> int64
//                /---^     /--^                                                                          \------v     \---v
// Floats: ~flt64 -> ~flt32 ----------------------------> {... -pi,-0.1,0,0.1,pi, ... } ----------------------> flt32 -> flt64
// int{1,8,16} all inject in flt32; int32 injects in flt64.  Small integer
// constants as floats inject into the integers.
//
// Named ints and flts "subtype", except for 0 which is canonically the same
// everywhere.  Example assuming a name "gal:flt32"
// ~flt64 -> ~flt32 -> gal:~flt32 -> {... gal:-pi, gal:-0.1, 0, gal:0.1, gal:pi, ... } -> gal:flt32 -> flt32 -> flt64
//
// OOPs are everywhere nullable; they always support a {oop+null, oop, null,
// oop&null} set of choices, where oop+null and oop&null are duals.
// Tuples are OOPS with an infinite list of values; the values are dualed
// structurally.
// Structs are tuples with a set of named fields; the fields can be top, a
// field name, or bottom.
// Strings are OOPs which again can be top, a constant, or bottom.
public class Type<T extends Type<T>> {
  static private int CNT=1;
  final int _uid=CNT++; // Unique ID, will have gaps, used to uniquely order Types in Unions
  byte _type;           // Simple types use a simple enum
  T _dual; // All types support a dual notion, lazily computed and cached here

  protected Type(byte type) { _type=type; }
  @Override public int hashCode( ) { return _type; }
  // Is anything equals to this?
  @Override public boolean equals( Object o ) {
    assert is_simple();         // Overridden in subclasses
    if( this == o ) return true;
    return (o instanceof Type) && _type==((Type)o)._type;
  }
  // In order to handle recursive printing, this is the only toString call in
  // the Type heirarchy.  Instead, subtypes override 'str(HashSet)' where the
  // HashSet is only installed by the head of a type-cycle (always and only
  // TypeName) and is used (again only by TypeName) to end cyclic printing.
  // All other 'str()' callers just pass along.
  @Override public final String toString() { return str(null); }
  String str( BitSet dups ) { return STRS[_type]; }

  // Object Pooling to handle frequent (re)construction of temp objects being
  // interned.  One-entry pool for now.
  private static Type FREE=null;
  protected T free( T ret ) { assert getClass()==Type.class; FREE=this; return ret; }
  static Type make( byte type ) {
    Type t1 = FREE;
    if( t1 == null ) t1 = new Type(type);
    else { FREE = null; t1._type = type; }
    Type t2 = t1.hashcons();
    return t1==t2 ? t1 : t1.free(t2);
  }
  // Hash-Cons - all Types are interned in this hash table.  Thus an equality
  // check of a (possibly very large) Type is always a simple pointer-equality
  // check, except during construction and intern'ing.
  private static HashMap<Type,Type> INTERN = new HashMap<>();
  final Type hashcons() {
    Type t2 = INTERN.get(this); // Lookup
    if( t2!=null ) {            // Found prior
      assert t2._dual != null;  // Prior is complete with dual
      assert t2 != this;        // Not hash-consing twice
      return t2;                // Return prior
    }
    // Not in type table
    _dual = null;                // No dual yet
    INTERN.put(this,this);       // Put in table without dual
    T d = xdual();               // Compute dual without requiring table lookup
    _dual = d;
    if( this==d ) return d;      // Self-symmetric?  Dual is self
    if( equals(d) ) { d.free(null); _dual=(T)this; return this; } // If self symmetric then use self
    assert d._dual==null;        // Else dual-dual not computed yet
    assert INTERN.get(d)==null;
    d._dual = (T)this;
    INTERN.put(d,d);
    return this;
  }
  // Remove a forward-ref type from the interning dictionary, prior to
  // interning it again - as a self-recursive type
  void untern( ) {
    Type rez  = INTERN.remove(this);
    assert rez != null;
  }
  void retern( ) {
    INTERN.put(this,this);
    assert INTERN.get(this)==this;
  }

  // Simple types are implemented fully here
  static final byte TALL    = 0; // Bottom
  static final byte TANY    = 1; // Top
  static final byte TCTRL   = 2; // Ctrl flow bottom
  static final byte TXCTRL  = 3; // Ctrl flow top (mini-lattice: any-xctrl-ctrl-all)
  static final byte TSCALAR = 4; // Scalars; all possible finite types that fit in a machine register; includes pointers (functions, structs), ints, floats; excludes state of Memory and Ctrl.
  static final byte TXSCALAR= 5; // Invert scalars
  static final byte TNUM    = 6; // Number and all derivatives (Complex, Rational, Int, Float, etc)
  static final byte TXNUM   = 7; // Any Numbers; dual of NUM
  static final byte TREAL   = 8; // All Real Numbers
  static final byte TXREAL  = 9; // Any Real Numbers; dual of REAL
  static final byte TSIMPLE =10; // End of the Simple Types
  private static final String[] STRS = new String[]{"all","any","Ctrl","~Ctrl","Scalar","~Scalar","Number","~Number","Real","~Real"};
  // Implemented in subclasses
  static final byte TNIL    =11; // Nil-types
  static final byte TNAME   =12; // Named types; always a subtype of some other type
  static final byte TOOP    =13; // Includes all GC ptrs & null; structs, strings.  Excludes functions, ints, floats
  static final byte TTUPLE  =14; // Tuples; finite collections of unrelated Types, kept in parallel
  static final byte TSTRUCT =15; // Structs; tuples with named fields
  static final byte TFUNPTR =16; // Function *pointer*, a "fat" pointer refering to a single block of code
  static final byte TFUN    =17; // Function signature; both domain and range are a Tuple; see TypeFun; many functions share the same signature
  static final byte TRPC    =18; // Return PCs; Continuations; call-site return points; see TypeRPC
  static final byte TFLT    =19; // All IEEE754 Float Numbers; 32- & 64-bit, and constants and duals; see TypeFlt
  static final byte TINT    =20; // All Integers, including signed/unsigned and various sizes; see TypeInt
  static final byte TSTR    =21; // String type
  static final byte TLAST   =22; // Type check

  public  static final Type ALL    = make( TALL   ); // Bottom
  public  static final Type ANY    = make( TANY   ); // Top
  public  static final Type CTRL   = make( TCTRL  ); // Ctrl
  public  static final Type XCTRL  = make(TXCTRL  ); // Ctrl
  public  static final Type  SCALAR= make( TSCALAR); // ptrs, ints, flts; things that fit in a machine register
  public  static final Type XSCALAR= make(TXSCALAR); // ptrs, ints, flts; things that fit in a machine register
          static final Type  NUM   = make( TNUM   );
  private static final Type XNUM   = make(TXNUM   );
  public  static final Type  REAL  = make( TREAL  );
  private static final Type XREAL  = make(TXREAL  );

  // Collection of sample types for checking type lattice properties.
  private static final Type[] TYPES = new Type[]{ALL,ANY,CTRL,XCTRL,SCALAR,XSCALAR,NUM,XNUM,REAL,XREAL};
  
  // The complete list of primitive types that are disjoint and also is-a
  // SCALAR; nothing else is a SCALAR except what is on this list (or
  // subtypes).  Useful when type-specializing functions to break SCALAR args
  // into a concrete list of specific types.  Specifically excludes e.g.
  // TypeTuple - these may be passed as a scalar reference type in the future
  // but for now Tuples explicitly refer to multiple values, and a SCALAR is
  // exactly 1 value.  
  private static /*final*/ Type[] SCALAR_PRIMS;
  
  private boolean is_simple() { return _type < TSIMPLE; }
  // Return base type of named types
  public Type base() { Type t = this; while( t._type == TNAME ) t = ((TypeName)t)._t; return t; }
  // Strip off any subclassing just for names
  byte simple_type() { return base()._type; }
  private boolean is_ptr() { byte t = simple_type();  return t == TOOP || t == TSTR || t == TSTRUCT || t == TTUPLE || t == TFUNPTR; }
  private boolean is_num() { byte t = simple_type();  return t == TNUM || t == TXNUM || t == TREAL || t == TXREAL || t == TINT || t == TFLT; }
  // True if 'this' isa SCALAR, without the cost of a full 'meet()'
  final boolean isa_scalar() { return _type != TCTRL && _type != TXCTRL; }
  
  // Return cached dual
  public final T dual() { return _dual; }
  
  // Compute dual right now.  Overridden in subclasses.
  protected T xdual() {
    assert is_simple();
    return (T)new Type((byte)(_type^1));
  }

  public final Type meet( Type t ) {
    Type mt = xmeet0(t);
    // Expensive asserts in an common place, turn off when stable
    //assert check_commute  (t,mt);
    //assert check_symmetric(t,mt);
    return mt;
  }
  private Type xmeet0( Type t ) {
    // Short cut for the self case
    if( t == this ) return this;
    // Short cut for the exactly dual case
    if( t._dual==this ) return above_center() ? t : this;    
    // Reverse; xmeet 2nd arg is never "is_simple" and never equal to "this"
    return !is_simple() && t.is_simple() ? t.xmeet(this) : xmeet(t);
  }
  
  // Compute meet right now.  Overridden in subclasses.
  // Handles cases where 'this.is_simple()' and unequal to 't'.
  // Subclassed xmeet calls can assert that '!t.is_simple()'.
  protected Type xmeet(Type t) {
    assert is_simple(); // Should be overridden in subclass
    // ANY meet anything is thing; thing meet ALL is ALL
    if( this==ALL || t==ANY ) return this;
    if( this==ANY || t==ALL ) return    t;

    // Ctrl can only meet Ctrl, XCtrl or Top
    byte type = (byte)(_type|t._type); // the OR is low if both are low
    if(  type <= TXCTRL ) return _type==TXCTRL && t._type==TXCTRL ? XCTRL : CTRL;
    if( _type <= TXCTRL || t._type <= TXCTRL ) return ALL;

    // Scalar is close to bottom: nearly everything falls to SCALAR, except
    // Bottom (already handled) and Control (error; already handled).
    if( _type == TSCALAR || t._type == TSCALAR ) return SCALAR;

    // ~Scalar is close to Top: it falls to nearly anything.
    if(   _type == TXSCALAR ) return t   ;
    if( t._type == TXSCALAR ) return this;

    // Scalar values break out into: nums(reals (int,flt)), GC-ptrs (structs(tuples), arrays(strings)), fun-ptrs, RPC
    if( t._type == TFUN   ) return SCALAR; // If 't' is a FUN and 'this' is not a FUN (because not equal to 't')
    if( t._type == TRPC   ) return SCALAR; // If 't' is a RPC and 'this' is not a RPC (because not equal to 't')
    // Named numbers or whatever: let name sort it out
    if( t._type == TNAME  ) return t.xmeet(this);
    if( t._type == TNIL   ) return t.xmeet(this);

    // Down to just nums and GC-ptrs
    boolean that_oop = t.is_ptr();
    boolean that_num = t.is_num();
    assert !(that_oop&&that_num);
    
    if( is_num() ) {
      // May be OOP0 or STR or STRUCT or TUPLE
      if( that_oop ) return SCALAR;
      if( !that_num ) throw AA.unimpl();
      // Numeric; same pattern as ANY/ALL, or SCALAR/XSCALAR
      if( _type == TNUM || t._type == TNUM ) return NUM;
      if(   _type == TXNUM ) return t   ;
      if( t._type == TXNUM ) return this;

      // Real; same pattern as ANY/ALL, or SCALAR/XSCALAR
      if( _type == TREAL || t._type == TREAL ) return REAL;
      if(   _type == TXREAL ) return t   ;
      if( t._type == TXREAL ) return this;
      throw AA.unimpl();        // Need nice printout
    }
    throw AA.unimpl();          // Need nice printout
  }

  // By design in meet, args are already flipped to order _type, which forces
  // symmetry for things with badly ordered _type fields.  The question is
  // still interesting for other orders.
  private boolean check_commute( Type t, Type mt ) {
    if( t==this ) return true;
    if( is_simple() && !t.is_simple() ) return true; // By design, flipped the only allowed order
    Type mt2 = t.xmeet(this);   // Reverse args and try again
    if( mt==mt2 ) return true;
    System.out.println("Meet not commutative: "+this+".meet("+t+")="+mt+", but "+t+".meet("+this+")="+mt2);
    return false;
  }
  private boolean check_symmetric( Type t, Type mt ) {
    if( t==this ) return true;
    Type ta = mt._dual.xmeet0(t._dual);
    Type tb = mt._dual.xmeet0(  _dual);
    if( ta==t._dual && tb==_dual ) return true;
    System.err.print("("+this+"&"+t+")=="+mt+" but \n("+mt._dual+"&");
    if( ta!=t._dual ) System.err.println(t._dual+")=="+ta+" \nwhich is not "+t._dual);
    else              System.err.println(  _dual+")=="+tb+" \nwhich is not "+  _dual);
    return false;
  }
  
  public Type join( Type t ) { return dual().meet(t.dual()).dual(); }

  public static void init0( HashMap<String,Type> types ) {
    types.put("real",REAL);
    TypeInt.init1(types);
    TypeFlt.init1(types);
    TypeStr.init1(types);
  }
  
  public static boolean check_startup() {
    Type[] ts =    Type      .TYPES ;
    ts = concat(ts,TypeInt   .TYPES);
    ts = concat(ts,TypeFlt   .TYPES);
    ts = concat(ts,TypeOop   .TYPES);
    ts = concat(ts,TypeNil   .TYPES);
    ts = concat(ts,TypeStr   .TYPES);
    ts = concat(ts,TypeTuple .TYPES);
    ts = concat(ts,TypeStruct.TYPES);
    ts = concat(ts,TypeFunPtr.TYPES);
    ts = concat(ts,TypeFun   .TYPES);
    ts = concat(ts,TypeRPC   .TYPES);
    ts = concat(ts,TypeName  .TYPES);

    // Confirm commutative & complete
    for( Type t0 : ts )
      for( Type t1 : ts ) {
        Type mt = t0.meet(t1);
        assert t0.check_commute  (t1,mt);
        assert t0.check_symmetric(t1,mt);
      }

    // Confirm associative
    int errs=0;
    for( Type t0 : ts )
      for( Type t1 : ts )
        for( Type t2 : ts ) {
          Type t01   = t0 .meet(t1 );
          Type t12   = t1 .meet(t2 );
          Type t01_2 = t01.meet(t2 );
          Type t0_12 = t0 .meet(t12);
          if( t01_2 != t0_12 && errs++ < 10 )
            System.err.println("("+t0+"&"+t1+") & "+t2+" == "+t0+" & ("+t1+" & "+t2+"); "+
                               "("+t01      +") & "+t2+" == "+t0+" & ("+t12        +"); "+
                               t01_2                  +" == "+t0_12);
        }
    assert errs==0 : "Found "+errs+" associative errors";


    // Confirm C-R, I guess.  If A isa B, then A.join(C) isa B.join(C)
    for( Type t0 : ts )
      for( Type t1 : ts ) {
        if( t0.isa(t1) ) {
          for( Type t2 : ts ) {
            Type t02 = t0.join(t2);
            Type t12 = t1.join(t2);
            Type mt  = t02.meet(t12);
            if( mt != t12 && errs++ < 10 ) {
              System.err.println("("+t0+" ^ "+t2+") = "+t02+"; "+
                                 "("+t1+" ^ "+t2+") = "+t12+"; "+
                                 "their meet = "+mt+" which is not "+t12);
            }
          }
        }
      }    
    assert errs==0 : "Found "+errs+" non-join-type errors";

    // Check scalar primitives; all are SCALARS and none sub-type each other.
    SCALAR_PRIMS = new Type[] { TypeInt.INT64, TypeFlt.FLT64, TypeOop.OOP, TypeFunPtr.GENERIC_FUNPTR, TypeRPC.ALL_CALL };
    for( Type t : SCALAR_PRIMS ) assert t.isa(SCALAR);
    for( int i=0; i<SCALAR_PRIMS.length; i++ ) 
      for( int j=i+1; j<SCALAR_PRIMS.length; j++ )
        assert !SCALAR_PRIMS[i].isa(SCALAR_PRIMS[j]);
    
    return true;
  }
  private static Type[] concat( Type[] ts0, Type[] ts1 ) {
    Type[] ts = Arrays.copyOf(ts0,ts0.length+ts1.length);
    System.arraycopy(ts1,0,ts,ts0.length,ts1.length);
    return ts;
  }
  
  // True if 'this' isa/subtypes 't'.  E.g. Int32-isa-Int64, but not vice-versa
  // E.g. ANY-isa-XSCALAR; XSCALAR-isa-XREAL; XREAL-isa-Int(Any); Int(Any)-isa-Int(3)
  public boolean isa( Type t ) { return meet(t)==t; }

  // True if value is above the centerline (no real value)
  public boolean above_center() {
    switch( _type ) {
    case TALL:
    case TCTRL:
    case TNUM:
    case TREAL:
    case TSCALAR:
      return false;             // These are all at or below center
    case TANY:
    case TXCTRL:
    case TXNUM:
    case TXREAL:
    case TXSCALAR:
      return true;              // These are all above center
    default: throw AA.unimpl(); // Overridden in subclass
    }
  }
  // True if value is higher-equal to SOME constant.
  public boolean may_be_con() {
    switch( _type ) {
    case TALL:
    case TSCALAR:
    case TNUM:
    case TREAL:
    case TCTRL:
      return false;             // These all include not-constant things
    case TANY:
    case TXREAL:
    case TXNUM:
    case TXSCALAR:
    case TXCTRL:
      return true;              // These all include some constants
    default: throw AA.unimpl();
    }
  }
  // True if exactly a constant (not higher, not lower)
  public boolean is_con() {
    switch( _type ) {
    case TALL:
    case TCTRL:
    case TNUM:
    case TREAL:
    case TSCALAR:
    case TANY:
    case TXCTRL:
    case TXNUM:
    case TXREAL:
    case TXSCALAR:
      return false;             // Not exactly a constant
    default: throw AA.unimpl(); // Overridden in subclass
    }
  }
  // Return the argument type of idxth argument.  Error for everybody except TypeFun
  public Type arg(int idx) { throw AA.unimpl(); }
  // Return any "return type" of the Meet of all function types.  Error for
  // everybody except TypeFun
  public Type ret() { throw AA.unimpl(); }
  // Return true if this is a forward-ref function pointer (return type from EpilogNode)
  public boolean is_forward_ref() { return false; }
  // Return the recursive type if this is a forward-ref type def, and null otherwise
  public TypeName merge_recursive_type( Type t ) { return null; }
  
  // Return a long   from a TypeInt constant; assert otherwise.
  public long   getl() { throw AA.unimpl(); }
  // Return a double from a TypeFlt constant; assert otherwise.
  public double getd() { throw AA.unimpl(); }
  // Return a String from a TypeStr constant; assert otherwise.
  public String getstr() { throw AA.unimpl(); }
  
  // Lattice of conversions:
  // -1 unknown; top; might fail, might be free (Scalar->Int); Scalar might lift
  //    to e.g. Float and require a user-provided rounding conversion from F64->Int.
  //  0 requires no/free conversion (Int8->Int64, F32->F64)
  // +1 requires a bit-changing conversion (Int->Flt)
  // 99 Bottom; No free converts; e.g. Flt->Int requires explicit rounding
  public byte isBitShape(Type t) {
    if( above_center() && isa(t) ) return 0; // Can choose compatible format
    if( _type == t._type ) return 0; // Same type is OK
    if( t._type==TSCALAR ) return 0; // Generic function arg never requires a conversion
    if( _type == TALL || _type == TSCALAR ) return -1; // Scalar has to resolve
    if( _type == TREAL && t.is_num() ) return -1; // Real->Int/Flt has to resolve

    throw typerr(t);  // Overridden in subtypes
  }
  // "widen" a narrow type for primitive type-specialization.
  // e.g. "3" becomes "int64".
  public Type widen() { return this; } // Overridden in subclasses
  // Operator precedence
  public byte op_prec() { return -1; } // Overridden in subclasses
  // Contains an error type string, perhaps embedded in some subtype
  public String errMsg() { return null; }
  // Make a (posssibly cyclic & infinite) named type.  Prevent the infinite
  // unrolling of names by not allowing a named-type with depth >= D from
  // holding (recursively) the head of a named-type cycle.  We need to cap the
  // unroll, to prevent loops/recursion from infinitely unrolling.
  Type make_recur(TypeName tn, int d, BitSet bs ) { assert is_simple(); return this; }
  
  RuntimeException typerr(Type t) {
    throw new RuntimeException("Should not reach here: internal type system error with "+this+(t==null?"":(" and "+t)));
  }
}
