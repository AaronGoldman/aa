package com.cliffc.aa.tvar;

import com.cliffc.aa.Env;
import com.cliffc.aa.node.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.*;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.TreeMap;

import static com.cliffc.aa.AA.DSP_IDX;
import static com.cliffc.aa.AA.unimpl;

/** Hindley-Milner based type variables.
 *
 * TV2s unify (ala Tarjan Union-Find), and can have structure such as "{ A -> B
 * }" or "@{ x = A, y = A }".  TV2s includes polymorphic structures and fields
 * (structural typing not duck typing), polymorphic nil-checking and an error
 * type.  TV2 types fully support recursive types.
 *
 * TV2 Bases include anything from the GCP lattice, and are generally sharper
 * than e.g. 'int'.  Bases with values of '3' and "abc" are fine.
 *
 * Function bases include the set of FIDXs used in the unification; this set is
 * generally less precise than that from GCP.  Function arguments that escape
 * have their GCP type widened "as if" called from the most HM-general legal
 * call site; otherwise GCP assumes escaping functions are never called and
 * their arguments have unrealistic high flow types.
 *
 * Unification typically makes many temporary type-vars and immediately unifies
 * them.  For efficiency, this algorithm checks to see if unification requires
 * an allocation first, instead of just "allocate and unify".  The major place
 * this happens is identifiers, which normally make a "fresh" copy of their
 * type-var, then unify.  I use a combined "make-fresh-and-unify" unification
 * algorithm there.  It is a structural clone of the normal unify, except that
 * it lazily makes a fresh-copy of the left-hand-side on demand only; typically
 * discovering that no fresh-copy is required.
 *
 * To engineer and debug the algorithm, the unification step includes a flag to
 * mean "actually unify, and report a progress flag" vs "report if progress".
 * The report-only mode is aggressively asserted for in the main loop; all
 * Syntax elements that can make progress are asserted as on the worklist.
 *
 * See HM.java for the prototype this is based from.
 *
 *
 * Mapping from a classic lambda-calculus AST to Sea-of-Nodes
 *
 * - Ids: HM identifiers are SSA edges in the Sea-of-Nodes.  A 'FreshNode' is
 *   used explicitly, for a id reference needing a 'fresh_unify'.  These appear
 *   for each display reference and reading ParmNodes to init the display.
 *   Other typical 'aa' ids are actually Loads agains the display.
 * - Lambda: FunPtrNodes (not FunNodes), which includes the display argument.
 * - Apply: CallEpiNodes (not CallNodes)
 * - Let: Strictly used to make new Displays at function headers and the parse
 *   start.  Normal 'aa' variables are field loads against the display.
 * - Struct: Struct
 * - Field: Field
 * - ??? : Loads and Stores directly modify memory state
 * - If: IfNode
 * - NotNil: Cast of not-nil.  Cast is used for other operations but is only
 *   polymorphic for nil.
 */


// This implementation of TV2 uses NBHM for the set of arguments, both field
// names and values; it is conveniently mutable and actually fairly expensive
// compared to e.g. a raw array.
//


public class TV2 {
  // All the aliases and fidxs that escape outside of Root, and might be called
  // with anything or appear on any Root input.
  private static BitsAlias EXT_ALIASES = BitsAlias.EXT;

  // Unique ID
  private static int UID=1;
  public final int _uid;

  // Structural parts to unify with, or null.
  // If Leaf   , then null and _flow is null.
  // If Base   , then null and _flow is set.
  // If unified, contains the single key ">>" and all other fields are null.
  // If Nil    , contains the single key "?"  and all other fields are null.
  // If Lambda , contains keys "x","y","z" for args or "ret" for return.
  // If Struct , contains keys for the field labels.  No display & not-null.
  // If Error  , _eflow may contain a 2nd flow type; also blends keys from all takers
  public NonBlockingHashMap<String,TV2> _args;

  // A dataflow type or null.  A 2nd dataflow type for errors.
  // If Leaf or unified or Nil or Apply, then null.
  // If Base, then the flow type.
  public Type _tflow, _eflow;

  // Can be nil
  boolean _may_nil;

  // Is a Lambda; keys x,y,z,ret may appear.
  boolean _is_fun;

  // True for TV2 returns from any primitive which might widen its result or
  // root args.  Otherwise, in cases like:
  //       "f0 = { f -> (if (rand) 1 (f (f0 f) 2))}; f0"
  // f's inputs and outputs gets bound to a '1': f = { 1 2 -> 1 }
  boolean _is_copy = true;

  // If null, not a struct.  If non-null, contains a Env.PROTO prototype or can
  // be the empty struct for no-prototype.
  String _clz;
  
  // Struct allows more fields.  Not quite the same as TypeStruct._def==Type.ANY
  boolean _open;

  // Null for no-error, or else a single-TV2 error
  String _err = null;

  // Set of dependent CallEpiNodes, to be re-worklisted if the called function changes TV2.
  private UQNodes _deps;

  private @NotNull final String _alloc_site; // Creation site; used to track excessive creation.

  // Track allocation statistics
  static private class ACnts { int _malloc, _unified, _free; }
  static private final HashMap<String,ACnts> ALLOCS = new HashMap<>(); // Counts at alloc sites

  // Common constructor
  private TV2(NonBlockingHashMap<String,TV2> args, @NotNull String alloc_site) {
    _uid=UID++;
    _args = args;
    ALLOCS.computeIfAbsent(_alloc_site=alloc_site,e -> new ACnts())._malloc++;
  }

  TV2 copy(String alloc_site) {
    // Shallow clone of args
    TV2 t = new TV2(_args==null ? null : (NonBlockingHashMap<String,TV2>)_args.clone(),alloc_site);
    t._tflow = _tflow;
    t._eflow = _eflow;
    t._may_nil = _may_nil;
    t._is_fun = _is_fun;
    t._clz = _clz;
    t._open = _open;
    t._is_copy = _is_copy;
    t._deps = _deps;
    t._err = _err;
    return t;
  }

  // Accessors
  public boolean is_leaf() { return _args==null && _tflow ==null && _clz==null && !_is_fun; }
  public boolean is_unified(){return _get(">>")!=null; }
  public boolean is_nil () { return _get("?" )!=null; }
  public boolean is_base() { return _tflow != null; }
  public boolean is_ptr () { return _get("*")!=null; }
  public boolean is_fun () { return _is_fun; }
  public boolean is_obj () { return _clz!=null; }
  public boolean is_open() { return _open; }           // Struct-specific
  public boolean is_err () { return _err!=null || is_err2(); }
  boolean is_err2()  { return
      (_tflow ==null ? 0 : 1) + // Any 2 or more set of _flow,_is_fun,_clz
      (_eflow ==null ? 0 : 1) + // Any 2 or more set of _flow,_is_fun,_clz
      (_tflow !=null && _args!=null ? 1 : 0) + // Base (flow) and also args
      (_is_fun ? 1 : 0) +
      (_clz == null ? 0 : 1) >= 2;
  }
  public boolean is_copy() { return _is_copy; }
  public boolean may_nil() { return _may_nil; }

  public int size() { return _args==null ? 0 : _args.size(); }

  // --------------------------------------------
  // Public factories
  // Make a new TV2 attached to a Node.
  public static TV2 make_leaf(@NotNull String alloc_site) {  return new TV2(null,alloc_site); }
  // Make a nilable
  public static TV2 make_nil(TV2 notnil, @NotNull String alloc_site) {
    TV2 t2 = new TV2(new NonBlockingHashMap<>(){{put("?",notnil);}},alloc_site);
    t2._may_nil = true;
    return t2;
  }
  // Make a new primitive base TV2
  public static TV2 make_base(Type flow, @NotNull String alloc_site) {
    assert !(flow instanceof TypeFunPtr) && !(flow instanceof TypeMemPtr);
    TV2 t2 = new TV2(null,alloc_site);
    t2._tflow =flow;
    assert t2.is_base();
    return t2;
  }
  
  public static TV2 make_fun(@NotNull String alloc_site, boolean open, TV2... t2s) {
    NonBlockingHashMap<String,TV2> args = new NonBlockingHashMap<>();
    for( int i=DSP_IDX; i<t2s.length-1; i++ )
      if( t2s[i]!=null ) args.put(argname(i), t2s[i]);
    args.put(" ret",t2s[t2s.length-1]);
    TV2 t2 = new TV2(args,alloc_site);
    t2._is_fun = true;
    t2._may_nil = false;
    t2._open = open; // May add more arguments
    return t2;
  }
  public static TV2 make_fun(@NotNull String alloc_site, TypeFunPtr tfp) {
    NonBlockingHashMap<String,TV2> args = new NonBlockingHashMap<>();
    for( int i=DSP_IDX; i<tfp.nargs(); i++ )
      args.put(argname(i), make_leaf(alloc_site));
    args.put(" ret",make_leaf(alloc_site));
    TV2 t2 = new TV2(args,alloc_site);
    t2._is_fun = true;
    t2._may_nil = false;
    return t2;
  }
  // A struct with fields, made from a StructNode.  Instances have a non-empty
  // clz.  Clz objects themselves are instances of the empty clz.  The _def
  // field is included.
  public static TV2 make_struct( StructNode rec, String alloc_site ) {
    NonBlockingHashMap<String,TV2> args = new NonBlockingHashMap<>();
    TypeStruct ts = rec.ts();
    if( !ts._clz.isEmpty() )
      args.put(" def",make_base(ts._def,alloc_site));
    for( int i=0; i<rec._defs._len; i++ )
      if( rec.in(i).has_tvar() )
        args.put(ts.get(i)._fld,rec.tvar(i));
    return make_struct(args,ts.clz(),alloc_site);
  }
  private static TV2 make_struct( TypeStruct ts, String alloc_site ) {
    NonBlockingHashMap<String,TV2> args = new NonBlockingHashMap<>();
    if( !ts._clz.isEmpty() )
      args.put(" def",make_base(ts._def,alloc_site));
    for( TypeFld fld : ts )
      //  args.put(fld._fld,make(fld._t));
      throw unimpl();
    return make_struct(args,ts.clz(),alloc_site);
  }
  private static TV2 make_struct( NonBlockingHashMap<String,TV2> args, String clz, String alloc_site ) {
    TV2 t2 = new TV2(args,alloc_site);
    t2._clz = clz;
    t2._may_nil = false;
    t2._open = false;
    return t2;
  }
  public void make_struct_from() {
    assert !is_obj();           // If error, might also be is_fun or is_base
    _clz = "";                  // No particular clazz
    _open = true;
    if( _args==null ) _args = new NonBlockingHashMap<>();
    assert is_obj();
  }
  public static TV2 make_ptr( TypeMemPtr flow, String alloc_site ) {
    NonBlockingHashMap<String,TV2> args = new NonBlockingHashMap<>(){{put("*",make_leaf(alloc_site));}};
    TV2 t2 = new TV2(args,alloc_site);
    t2._tflow =flow;
    assert t2.is_ptr();
    return t2;
  }

  // An array, with int length and an element type
  public static TV2 make_ary(NewNode n, Node elem, String alloc_site) {
    //NonBlockingHashMap<String,TV2> args = new NonBlockingHashMap<>();
    //args.put(" len",  make_leaf(n,alloc_site));
    //args.put(" elem", elem.tvar());
    //TV2 t2 = new TV2(args,UQNodes.make(n),alloc_site);
    //t2._flow = n._tptr;
    //assert t2.is_obj();
    //return t2;
    throw unimpl();
  }

  public static TV2 make(Type t, String alloc_site) {
    return switch( t ) {
    case TypeStruct ts -> make_struct(ts,alloc_site);
    case TypeFlt f -> make_base(t,alloc_site);
    case TypeInt i -> make_base(t,alloc_site);
    case TypeFunPtr tfp ->
      make_fun(alloc_site,tfp);
    case TypeNil n -> {
      if( t == TypeNil.XNIL )
        yield make_nil(make_leaf(alloc_site),alloc_site);
      yield make_leaf(alloc_site);
    }
    case Type tt -> {
      if( t==Type.ANY ) yield make_leaf(alloc_site);
      if( t==Type.ALL ) yield make_base(t,alloc_site);
      throw unimpl();
    }
    };
  }

  public static void reset_to_init0() {
    UID=1;
    EXT_ALIASES = BitsAlias.EXT;
  }

  public void free() {
    if( !is_unified() ) ALLOCS.get(_alloc_site)._free++;
    _args = null;
    _tflow = _eflow = null;
    _open = false;
    _deps = null;
    _err  = null;
  }

  // Functions have argument names, but call sites do not and might also be
  // mixing up different functions with different arg names.  Use these
  // arg-names.
  public static final String[] ARGS = new String[] {"bad0","bad1","2","3","4","5"};
  public static String argname(int i) {
    if( i < ARGS.length ) return ARGS[i];
    throw unimpl();
  }


  // --------------------------------------------

  // Get at a key, withOUT U-F rollup.  Used for debug printing.
  private TV2 _get( String key ) { return _args==null ? null : _args.get(key); }
  // Get at a key, with U-F rollup
  public TV2 arg( String key ) {
    TV2 tv = _get(key);
    if( tv==null ) return null;
    TV2 tv2 = tv.find();
    if( tv == tv2 ) return tv;
    _args.put(key,tv2);
    return tv2;
  }

  // Tarjan U-F find, without the roll-up.  Used for debug printing and asserts
  public TV2 debug_find() {
    if( !is_unified() ) return this;
    if( _args==null ) return this;
    TV2 u = _args.get(">>");
    if( !u.is_unified() ) return u;  // Shortcut

    // U-F search, no fixup
    int cnt=0;
    while( u.is_unified() && cnt++<100 ) u = u._args.get(">>");
    assert cnt<100;             // Infinite roll-up loop
    return u;
  }

  // Classic Tarjan U-F with rollup
  public TV2 find() {
    TV2 top = _find0();
    return top.is_nil() ? top._find_nil() : top;
  }

  private TV2 _find0() {
    TV2 top = debug_find();
    if( top == this ) return top;
    if( top==_args.get(">>") ) return top;
    TV2 v = this, next;           // Rerun, rolling up to top
    while( (next=v._args.get(">>"))!=top ) { v._args.put(">>",top); v = next; }
    return top;
  }

  // Nilable fixup.  nil-of-leaf is OK.  nil-of-anything-else folds into a
  // nilable version of the anything-else.
  private TV2 _find_nil() {
    TV2 n = arg("?");
    if( n.is_leaf() ) return this;
    _args.remove("?");  // No longer have the "?" key, not a nilable anymore
    //_args.put("??",n);  // For hm_apply_lift, keep around the prior mapping
    _add_nil(n);
    if( _args.size()==0 ) _args=null;
    n.merge_deps(this);
    return this;
  }
  private TV2 _add_nil(TV2 n) {
    // Nested nilable-and-not-leaf, need to fixup the nilable
    if( n.is_base() ) {
      _may_nil = false;
      _tflow = n._tflow.meet(TypeNil.XNIL);
      if( n._eflow!=null ) _eflow = n._eflow.meet(TypeNil.XNIL);
      if( !n._is_copy ) clr_cp();
    }
    if( n.is_ptr() ) {
      if( _args==null ) _args = new NonBlockingHashMap<>();
      _args.put("*",n.arg("*"));
    }
    if( n.is_fun() ) throw unimpl();
    if( n.is_obj() ) {
      // Recursively add-nil the fields
      for( String key : n._args.keySet() ) {
        TV2 arg = n.arg(key);
        _args.put(key,arg.copy("add_nil")._add_nil(arg));
      }
      _clz = n._clz;
      _may_nil = true;
      _open = n._open;
    }
    if( n.is_nil() )            // Peel nested is_nil
      _args.put("?",n.arg("?"));
    return this;
  }

  // Get at a key, with U-F rollup.  The key is looked up both locally, and in
  // a clazz - this avoids eagerly making all final fields in all instances.
  // If DOES add the key locally, so should not be called with testing.
  TV2 clz_arg( String key ) {
    TV2 tv = _get(key);
    if( tv==null ) tv = _clz_arg(key);
    if( tv==null ) return null;
    TV2 tv2 = tv.find();
    if( tv == tv2 ) return tv;
    _args.put(key,tv2);
    return tv2;
  }
  // Look up the field key in the clazz instance, if any.  If it exists, it
  // should be a final function field with a display which will bind to this
  // instance.
  
  // Eg, looking up _+_ in int:@{ def=3 } ends up with:
  //    SELF:int:@{ def=3; _+_ = { SELF ... -> Vleaf } }
  // And the clz:
  //             @{        _+_ = { Vdsp ... -> Vleaf } }  
  TV2 _clz_arg( String key ) {
    if( _clz==null || _clz.isEmpty() ) return null;
    // Clazz must exist, but might not be unified/structured yet.
    StructNode sclz = Env.PROTOS.get(_clz);
    TV2 clz = sclz.tvar();
    // If not yet a clazz, make it one
    if( !clz.is_obj() ) {
      clz._clz = "";            // Clazzes do not have a clazz.
      if( clz._args==null ) clz._args = new NonBlockingHashMap<>();
    }
    // Find the field, making if needed.
    TV2 fld = clz.arg(key);
    // At least a leaf field in the clazz
    if( fld==null )
      clz._args.put(key,fld = make_leaf("clz_arg"));
    // At least the leaf is a function, taking a display and allowing more arguments
    if( !fld.is_fun() ) {
      if( fld._args==null ) fld._args = new NonBlockingHashMap<String,TV2>();
      fld._args.put(argname(DSP_IDX),make_leaf("clz_arg dsp"));
      fld._args.put(" ret",make_leaf("clz_arg ret"));
      fld._is_fun=true;
      fld._open=true;
    }
    // Put a fresh copy in the local instance
    _args.put(key,fld=fld._fresh(null));
    TV2 dsp = fld.arg(argname(DSP_IDX));
    dsp._unify(this,false);
    return fld;
  }
  
  
  private long dbl_uid(TV2 t) { return dbl_uid(t._uid); }
  private long dbl_uid(long uid) { return ((long)_uid<<32)|uid; }

  // Strip off nil
  public TV2 strip_nil() {
    if( _tflow != null ) _tflow = _tflow.join(TypeNil.NSCALR);
    if( _eflow != null ) _eflow = _eflow.join(TypeNil.NSCALR);
    _may_nil = false;
    return this;
  }

  // Varies as unification happens; not suitable for a HashMap/HashSet unless
  // unchanging (e.g. defensive clone)
  int _hash;
  @Override public int hashCode() { return _hash==0 ? (_hash=compute_hash()) : _hash;  }
  private int compute_hash() {
    int hash = 0;
    if( _tflow!=null ) hash += _tflow._hash;
    if( _eflow!=null ) hash += _eflow._hash;
    if( _is_fun ) hash = (hash+ 7)*11;
    if( _may_nil) hash = (hash+19)*23;
    if( _clz!=null ) hash ^= _clz.hashCode();
    if( _args!=null )
      for( String key : _args.keySet() )
        hash ^= key.hashCode();
    return hash==0 ? 0xdeadbeef : hash;
  }

  // True if changes (or would change if testing)
  public boolean set_err(String err, boolean test) {
    if( err==null ) return false;
    if( Util.eq(err,_err) ) return false;
    if( _err !=null ) throw unimpl();  // Merge unrelated errors
    if( !test ) _err = err;
    return true;                // Changed
  }

  // -----------------
  // recursively build a conservative flow type from an HM type.  The HM
  // is_obj wants to be a TypeMemPtr, but the recursive builder is built
  // around TypeStruct.

  // No function arguments, just function returns.
  static final NonBlockingHashMapLong<Type> ADUPS = new NonBlockingHashMapLong<>();
  public Type as_flow() {
    assert ADUPS.isEmpty();
    Type t = _as_flow();
    ADUPS.clear();
    return t;
  }
  Type _as_flow() {
    assert !is_unified();
    if( is_leaf() ) return TypeNil.SCALAR;
    if( is_base() ) return _tflow;
    if( is_nil()  )
      return arg("?")._as_flow().meet(TypeNil.NIL);
    if( is_fun()  ) {
      Type tfun = ADUPS.get(_uid);
      if( tfun != null ) return tfun;  // TODO: Returning recursive flow-type functions
      ADUPS.put(_uid, TypeNil.XSCALAR);
      Type rez = arg(" ret")._as_flow();
      //return TypeFunPtr.make(EXT_FIDXS,size()-1,Type.ANY,rez);
      throw unimpl();
    }
    if( is_obj() ) {
      TypeStruct tstr = (TypeStruct)ADUPS.get(_uid);
      if( tstr==null ) {
        //// Returning a high version of struct
        //Type.RECURSIVE_MEET++;
        //tstr = TypeStruct.malloc("",is_open() ? Type.ANY : Type.ALL).add_fld(TypeFld.NO_DISP);
        //if( _args!=null )
        //  for( String id : _args.keySet() )
        //    tstr.add_fld(TypeFld.malloc(id));
        //ADUPS.put(_uid,tstr); // Stop cycles
        //if( _args!=null )
        //  for( String id : _args.keySet() )
        //    tstr.get(id).setX(arg(id)._as_flow()); // Recursive
        //if( --Type.RECURSIVE_MEET == 0 )
        //  // Shrink / remove cycle dups.  Might make new (smaller)
        //  // TypeStructs, so keep RECURSIVE_MEET enabled.
        //  tstr = Cyclic.install(tstr);
        throw unimpl();
      }
      // The HM is_struct wants to be a TypeMemPtr, but the recursive builder
      // is built around TypeStruct, hence the TMP wrap.

      // This is a Root passed-in struct which can have all aliases
      return TypeMemPtr.make(_may_nil,EXT_ALIASES,tstr);
    }

    throw unimpl();
  }


  // -----------------
  // U-F union; 'this' becomes 'that'.  No change if only testing, and reports
  // progress.  If progress and not testing, adds _deps to worklist.
  public boolean union(TV2 that, boolean test) {
    assert !is_unified() && !that.is_unified();
    if( this==that ) return false;
    if( test ) return true; // Report progress without changing

    // Merge all the hard bits
    that._is_fun  |= _is_fun;
    that._may_nil |= _may_nil;
    if( _clz!=null ) {
      if( that._clz!=null ) {
        if( Util.eq(_clz,that._clz) || _clz.isEmpty() ) ; // No subclass
        else if( that._clz.isEmpty() ) that._clz = _clz;  // Take subclass
        else throw unimpl();                              // Merge unrelated subclazzes
      }
      that._open = that._clz!=null ? (that._open & _open) : _open;
    }
    // Merge all the hard bits
    unify_base(that,test);

    // Merge arguments
    if( _args!=null ) {
      if( that._args==null ) { that._args = _args; _args=null; }
      else that._args.putAll(_args);
    }
    // Merge errors
    if( _err!=null && that._err==null ) that._err = _err;
    else if( _err!=null && !Util.eq(_err,that._err) )
      throw unimpl();         // TODO: Combine single errors

    // Work all the deps
    that.add_deps_flow();
    this.add_deps_flow();      // Any progress, revisit deps
    // Hard union this into that, no more testing.
    return _union(that);
  }

  // Unify this._tflow into that._tflow.  Flow is limited to only one of
  // {int,flt,ptr} and a 2nd unrelated flow type is kept as an error in
  // that._eflow.  Basically a pick the max 2 of 4 values, and each value is
  // range 0-3.  Returns progress.
  boolean unify_base(TV2 that, boolean test) {
    boolean progress = false;
    if( that._is_copy && !_is_copy )  { // Progress if setting is_copy
      if( test ) return true;
      progress = true;
      that.clr_cp();
    }
    Type sf = _tflow, hf = that._tflow;     // Flow of self and that.
    if( sf==null && hf==null ) return progress;// Fast cutout
    Type se = _eflow, he = that._eflow;     // Error flow of self and that.
    Type of = that._tflow, oe = that._eflow; // Old versions, to check for progress
    int cmp =  _fpriority(sf) - _fpriority(hf);
    if( cmp == 0 ) { that._tflow = sf.meet(hf); sf = se; hf = he; } // Tied; meet; advance both
    if( cmp  > 0 ) { that._tflow = sf;          sf = se;          } // Pick winner, advance
    if( cmp  < 0 ) {                            hf = he;          } // Pick winner, advance
    if( !(sf==null && hf==null) ) {                                 // If there is an error flow
      int cmp2 =  _fpriority(sf) - _fpriority(hf); // In a triple-error, pick best two
      if( cmp2 == 0 ) that._eflow = sf.meet(hf);
      if( cmp2  > 0 ) that._eflow = sf;
      if( cmp2  < 0 ) that._eflow = hf;
    }
    progress |= of!=that._tflow || oe!=that._eflow; // Progress check
    if( test && progress ) { that._tflow=of; that._eflow=oe; } // Unwind if just testing
    return progress;
  }
  // Sort flow types; int >> flt >> ptr >> null
  private static int _fpriority( Type t0 ) {
    if( t0 instanceof TypeInt ) return 4;
    if( t0 instanceof TypeFlt ) return 3;
    if( t0 instanceof TypeMemPtr ) return 2;
    if( t0 == Type.ALL ) return 1;
    assert t0==null;
    return 0;
  }

  // Union this into that; this can already be unified (if rolling up).
  // Crush all the extra fields in this, to avoid accidental usage.
  private boolean _union(TV2 that) {
    assert !is_unified() && !that.is_unified(); // Cannot union twice
    ALLOCS.get(_alloc_site)._unified++;
    merge_deps(that);           // Merge update lists, for future unions
    if( _args!=null ) _args.clear();
    else _args = new NonBlockingHashMap<>();
    _args.put(">>", that);
    _tflow = _eflow = null;
    _is_fun = _may_nil = _open = false;
    _is_copy = true;
    _deps = null;
    _err = _clz = null;
    assert is_unified();
    return true;
  }

  // U-F union; this is nilable and becomes that.
  // No change if only testing, and reports progress.
  boolean unify_nil(TV2 that, boolean test) {
    assert !is_nil() && that.is_nil();
    if( test ) return true; // Will make progress
    TV2 leaf = that.arg("?");  assert leaf.is_leaf();
    leaf.add_deps_flow();
    // Clone the top-level struct and make this nilable point to the clone;
    // this will collapse into the clone at the next find() call.
    TV2 copy = copy("unify_nil").strip_nil();
    // Unify the nilable leaf into that.
    return leaf.union(copy,test) | _union(that);
  }

  // U-F union; that is nilable and a fresh copy of this becomes that.  No change
  // if only testing, and reports progress.  Handles cycles in the fresh side.
  boolean unify_nil(TV2 that, boolean test, TV2[] nongen) {
    assert !is_nil() && that.is_nil();
    if( test ) return true; // Will make progress;
    TV2 leaf = that.arg("?");  assert leaf.is_leaf();
    // A shallow copy and fresh-unify fails if 'this' is cyclic, because the
    // shallow copy peels one part of the loop.
    TV2 copy = _fresh(nongen).strip_nil();
    copy._unify(leaf,true);
    return vput(that,true);
  }

  // --------------------------------------------
  // Cyclic (structural) equals
  static private final HashMap<TV2,TV2> CDUPS = new HashMap<>();
  public final boolean eq( TV2 that ) {
    assert CDUPS.isEmpty();
    boolean eq = _eq(that);
    CDUPS.clear();
    return eq;
  }
  private boolean _eq( TV2 that) {
    assert !is_unified() && !that.is_unified();
    if( this==that ) return true;
    if( _tflow !=that._tflow ||  // Base cases have to be bitwise identical
        _eflow  !=that._eflow  ||
        _may_nil!=that._may_nil||
        _is_fun !=that._is_fun ||
        !Util.eq(_clz,that._clz) || 
        !Util.eq(_err,that._err) ) // Error strings equal
      return false;
    if( is_leaf() ) return false; // Two leaves must be the same leaf, already checked for above
    if( _args==that._args ) return true;      // Same arrays (generally both null)
    if( size() != that.size() ) return false; // Mismatched sizes

    // Cycles stall the equal/unequal decision until we see a difference.
    TV2 tc = CDUPS.get(this);
    if( tc!=null )  return tc==that; // Cycle check; true if both cycling the same
    CDUPS.put(this,that);

    // Structural recursion
    for( String key : _args.keySet() ) {
      TV2 arg = that.arg(key);
      if( arg==null || !arg(key)._eq(arg) )
        return false;
    }
    return true;
  }

  // --------------------------------------------
  // Used in the recursive unification process.  During unify detects cycles,
  // to allow cyclic unification.
  private static final NonBlockingHashMapLong<TV2> DUPS = new NonBlockingHashMapLong<>();
  // Structural unification.  Both 'this' and that' are the same afterwards.
  // Returns True if progressed.
  public boolean unify(TV2 that, boolean test) {
    if( this==that ) return false;
    assert DUPS.isEmpty();
    boolean progress = _unify(that,test);
    DUPS.clear();
    return progress;
  }

  // Structural unification, 'this' into 'that'.  No change if just testing
  // (work is null) and returns a progress flag.  If updating, both 'this'
  // and 'that' are the same afterwards.
  private boolean _unify(TV2 that, boolean test) {
    assert !is_unified() && !that.is_unified();
    if( this==that ) return false;

    // Any leaf immediately unifies with any non-leaf
    if( this.is_leaf() && that.is_leaf() && _uid<that._uid )
      return that.union(this,test); // Two leafs sort by _uid
    if( this.is_leaf() ) return this.union(that,test);
    if( that.is_leaf() ) return that.union(this,test);

    // Two bases unify by smaller uid
    if( is_base() && that.is_base() )
      return _uid<that._uid ? that.union(this,test) : this.union(that,test);

    // Mismatched error pointers require a hard sub-part unify
    TV2 ptr0 = arg("*"), ptr1 = that.arg("*");
    if( ptr0!=null && ptr1 != null )
      ptr0._unify(ptr1,test);

    // Special case for nilable union something
    if( this.is_nil() && !that.is_nil() ) return that.unify_nil(this,test);
    if( that.is_nil() && !this.is_nil() ) return this.unify_nil(that,test);

    // Cycle check.
    long luid = dbl_uid(that);  // long-unique-id formed from this and that
    TV2 rez = DUPS.get(luid);
    assert rez==null || rez==that;
    if( rez!=null ) return false; // Been there, done that
    DUPS.put(luid,that);          // Close cycles

    if( test ) return true; // Here we definitely make progress; bail out early if just testing

    // Structural recursion unification.
    if( (is_obj() && that.is_obj()) ||
        (is_fun() && that.is_fun()) ||
        (is_nil() && that.is_nil()) ||
        (is_ptr() && that.is_ptr()) )
      unify_flds(this,that);
    return find().union(that.find(),test);
  }

  // Structural recursion unification.  Called nested, and called by NotNil
  // at the top-level directly.
  static void unify_flds(TV2 thsi, TV2 that) {
    if( thsi._args==that._args ) return;  // Already equal (and probably both nil)
    for( String key : thsi._args.keySet() ) {
      TV2 fthis = thsi.    arg(key); // Field of this
      TV2 fthat = that.clz_arg(key); // Field of that, maybe in a clazz
      if( fthat==null ) {        // Missing field in that
        if( that.is_open() ) that.add_fld(key,fthis); // Add to RHS
        else                 thsi.del_fld(key); // Remove from LHS
      } else fthis._unify(fthat,false); // Normal matching field unification
      // Progress may require another find()
      thsi=thsi.find();
      that=that.find();
    }
    // Fields on the RHS are aligned with the LHS also
    if( that._args!=null )
      for( String key : that._args.keySet() )
        if( thsi.arg(key)==null ) { // Missing field in this
          if( thsi.is_open() )  thsi.add_fld(key,that.arg(key)); // Add to LHS
          else                  that.del_fld(key);  // Drop from RHS
        }

    if( that.debug_find() != that ) throw unimpl(); // Missing a find
  }

  // Insert a new field
  public void add_fld( String id, TV2 fld) {
    if( _args==null ) _args = new NonBlockingHashMap<>();
    fld.push_deps(_deps);
    _args.put(id,fld);
    add_deps_flow();
  }
  // Delete a field
  private void del_fld( String id ) {
    if( Util.eq("*"  ,id) || // Do not break ptr-ness, instead keep field and will be an error
        Util.eq("ret",id) || // Do not break function-ness
        id.charAt(0)==' ' )  // Also leave function args
      return;
    add_deps_flow();
    _args.remove(id);
    if( _args.size()==0 )  _args=null;
  }

  // -----------------
  // Used in the recursive unification process.  During fresh_unify tracks the
  // mapping from LHS TV2s to RHS TVs.
  private static final HashMap<TV2,TV2> VARS = new HashMap<>();
  private static int FCNT;

  // Make a (lazy) fresh copy of 'this' and unify it with 'that'.  This is
  // the same as calling 'fresh' then 'unify', without the clone of 'this'.
  // The TV2[] is used when making the 'fresh' copy for the occurs_check.

  // Returns progress.
  // If test, we are testing only and make no changes.
  public boolean fresh_unify(TV2 that, TV2[] nongen, boolean test) {
    FCNT=0;
    assert VARS.isEmpty() && DUPS.isEmpty();
    boolean progress = _fresh_unify(that,nongen,test);
    VARS.clear();  DUPS.clear();
    return progress;
  }

  // Apply 'this' structure on 'that'; no modifications to 'this'.  VARS maps
  // from the cloned LHS to the RHS replacement.
  private boolean _fresh_unify(TV2 that, TV2[] nongen, boolean test ) {
    assert !is_unified() && !that.is_unified();

    // Check for cycles
    TV2 prior = VARS.get(this);
    if( prior!=null )                        // Been there, done that
      return prior.find()._unify(that,test); // Also 'prior' needs unification with 'that'
    // Check for equals (internally checks this==that)
    if( eq(that) ) return vput(that,false);

    // Famous 'occurs-check': In the non-generative set, so do a hard unify,
    // not a fresh-unify.
    if( nongen_in( nongen ) ) return vput(that,_unify(that,test));

    // LHS leaf, RHS is unchanged but goes in the VARS
    if( this.is_leaf() ) return vput(that,false);
    if( that.is_leaf() )  // RHS is a tvar; union with a deep copy of LHS
      return test || vput(that,that.union(_fresh(nongen),test));

    // Special handling for nilable
    if( this.is_nil() && !that.is_nil() )
      return vput(that,that.unify_nil_this(test));
    // That is nilable and this is not
    if( that.is_nil() && !this.is_nil() )
      return unify_nil(that,test,nongen);

    // Progress on the parts
    boolean progress = false;
    if( _tflow !=null ) progress = unify_base(that, test);
    
    // Check for mismatched LHS and RHS
    if( test ) {
      if( _may_nil && !that._may_nil ) return true;
      if( is_ptr() && !that.is_ptr() ) return true;
      if( is_fun() && !that.is_fun() ) return true;
      if( is_obj() && !that.is_obj() ) return true;
      if( _err!=null && !Util.eq(_err,that._err) ) return true;
    } else {
      if( _may_nil && !that._may_nil ) { progress = that._may_nil = true; }
      if( is_ptr() && !that.is_ptr() ) // Error, fresh_unify a ptr into a non-ptr non-leaf
        //return vput(that,that._unify(_fresh(nongen),work));
        throw unimpl();         // TODO: direct fresh-unify?
      if( is_fun() && !that.is_fun() ) // Error, fresh_unify a fun into a non-fun non-leaf
        that.cp_args(_args)._is_fun=progress=true;
      if( is_obj() && !that.is_obj() ) { // Error, fresh_unify a struct into a non-struct non-leaf
        that.cp_args(_args);
        that._clz = _clz;
        progress=true;
      }
      if( _err!=null && !Util.eq(_err,that._err) ) {
        if( that._err!=null ) throw unimpl(); // TODO: Combine single error messages
        else { // Error, fresh_unify an error into a non-leaf non-error
          progress = true;
          that._err = _err;
        }
      }
    }

    vput(that,progress);        // Early set, to stop cycles
    // Both same (probably both nil)
    if( _args==that._args ) return progress;

    // Structural recursion unification, lazy on LHS.  Fields in both sides are
    // directly unified.  Fields on one side check to see if the other side is
    // open; if open the field is copied else deleted.

    // The other major compilation is prototypes:
    // CNC... fresh prototype vs not fresh...
    // LHS does not care, but needs to iterate prototype args
    // RHS DOES care
    
    FCNT++;            // Recursion count on Fresh
    assert FCNT < 100; // Infinite _fresh_unify cycles
    boolean missing = size()!= that.size();
    if( _args != null )
      throw unimpl();
    //for( String key : _args.keySet() ) {
    //  TV2 lhs =      get(key);  assert lhs!=null;
    //  TV2 rhs = that.get(key);
    //  if( rhs==null ) {         // No RHS to unify against
    //    if( that.open() ) {     // If RHS is open, copy field into it
    //      if( test ) return true; // Will definitely make progress
    //      progress |= that.add_fld(key,lhs._fresh(nongen));
    //    } // If closed, no copy
    //  } else {
    //    progress |= lhs._fresh_unify(rhs,nongen,test);
    //  }
    //  if( (that=that.find()).is_err() ) return true;
    //  if( progress && test ) return true;
    //}
    FCNT--;
    //// Fields in RHS and not the LHS are also merged; if the LHS is open we'd
    //// just copy the missing fields into it, then unify the structs (shortcut:
    //// just skip the copy).  If the LHS is closed, then the extra RHS fields
    //// are removed.
    //if( !open() )
    //  for( String id : that.args() )      // For all fields in RHS
    //    if( get(id)==null ) {             // Missing in LHS
    //      if( test ) return true;         // Will definitely make progress
    //      { that._args.remove(id); progress=true; } // Extra fields on both sides are dropped
    //    }
    //Type mt = that._type.meet(_type);   // All aliases
    //boolean open = that._open & _open;
    //if( that._open != open || that._type != mt ) progress = true;
    //if( test && progress ) return true;
    //that._open = open; // Pick up open stat
    //that._type = mt;   // Pick up all aliases
    //
    //return progress;
    throw unimpl();
  }

  private boolean vput(TV2 that, boolean progress) { VARS.put(this,that); return progress; }
  private TV2 vput(TV2 that) { VARS.put(this,that); return that; }

  private boolean unify_nil_this( boolean test ) {
    if( test ) return unify_nil_this_test();
    boolean progress = false;
    Type tmt = meet_nil(_tflow); if( progress |= (tmt!=_tflow) ) _tflow = tmt;
    Type emt = meet_nil(_eflow); if( progress |= (emt!=_eflow) ) _eflow = emt;
    if( !_may_nil && !is_base() ) { progress = _may_nil = true; }
    if( progress ) add_deps_flow();
    return progress;
  }
  private boolean unify_nil_this_test( ) {
    if( meet_nil(_tflow)!=_tflow ) return true;
    if( meet_nil(_eflow)!=_eflow ) return true;
    if( !_may_nil && !is_base() ) return true;
    return false;
  }
  private static Type meet_nil(Type t) { return t==null ? null : t.meet(TypeNil.XNIL); }
  private TV2 cp_args(NonBlockingHashMap<String,TV2> args ) {
    if( _args==null )
      _args = (NonBlockingHashMap<String,TV2>)args.clone(); // Error case; bring over the args
    return this;
  }
  

  public TV2 fresh(TV2[] nongen) {
    assert VARS.isEmpty();
    TV2 tv2 = _fresh(nongen);
    VARS.clear();
    return tv2;
  }
  private TV2 _fresh(TV2[] nongen) {
    assert !is_unified();       // Already chased these down
    TV2 rez = VARS.get(this);
    if( rez!=null ) return rez.find(); // Been there, done that
    // Unlike the original algorithm, to handle cycles here we stop making a
    // copy if it appears at this level in the nongen set.  Otherwise we'd
    // clone it down to the leaves - and keep all the nongen leaves.  Stopping
    // here preserves the cyclic structure instead of unrolling it.
    if( nongen_in(nongen) )  return vput(this);

    TV2 t = copy("_fresh_copy");
    if( is_leaf() ) t._deps=null;
    VARS.put(this,t);       // Stop cyclic structure looping
    if( _args!=null )
      for( String key : _args.keySet() )
        t._args.put(key,arg(key)._fresh(nongen));
    assert !t.is_unified();
    return t;
  }

  // --------------------------------------------
  private static final VBitSet ODUPS = new VBitSet();

  boolean _occurs_in_type(TV2 x) {
    assert !is_unified() && !x.is_unified();
    if( x==this ) return true;
    if( ODUPS.tset(x._uid) ) return false; // Been there, done that
    if( x._args!=null )
      for( String key : x._args.keySet() )
        if( _occurs_in_type(x.arg(key)) )
          return true;
    return false;
  }

  public boolean nongen_in(TV2[] vs) {
    if( vs==null ) return false;
    ODUPS.clear();
    for( TV2 t2 : vs )
      if( _occurs_in_type(t2.find()) )
        return true;
    return false;
  }

  // --------------------------------------------
  // Attempt to lift a GCP call result, based on HM types.  Walk the input HM
  // type and GCP flow type in parallel and create a mapping.  Then walk the
  // output HM type and GCP flow type in parallel, and join output GCP types
  // with the matching input GCP type.
  public  static final NonBlockingHashMap  <TV2,Type> T2MAP = new NonBlockingHashMap<>();
  private static boolean HAS_OPEN;
  public  static final NonBlockingHashMapLong<TypeStruct> WDUPS = new NonBlockingHashMapLong<>();
  private static final BitSet WBS = new BitSet();

  // Lift the flow Type of an Apply, according to its inputs.  This is to help
  // preserve flow precision across polymorphic calls, where the input flow
  // types all meet - but HM understands how the TV2s split back apart after the
  // Apply.  During this work, every TV2 is mapped one-to-one to a flow Type,
  // and the mapping is made recursively.

  // Walk a TV2 and a matching flow-type, and build a map from TV2 to flow-types.
  // Stop if either side loses corresponding structure.  This operation must be
  // monotonic because the result is JOINd with GCP types.
  public Type walk_types_in(TypeMem tmem, Type t) {
    //assert !is_unified();
    //if( WDUPS.putIfAbsent(dbl_uid(t._uid),TypeStruct.ALLSTRUCT)!=null ) return t;
    //if( is_err() ) return fput(Type.SCALAR); //
    //// Base variables (when widened to an HM type) might force a lift.
    //if( is_base() ) return fput(_type.meet(t));
    //// Free variables keep the input flow type.
    //if( is_leaf() ) return fput(t);
    //// Nilable
    //if( is_nil() )
    //  return get("?").walk_types_in(tmem,fput(t.join(Type.NSCALR)));
    //
    //// Functions being called or passed in can have their return types appear
    //// in the call result.
    //if( is_fun() ) {
    //  if( !(t instanceof TypeFunPtr) ) return t; // Typically, some kind of error situation
    //  fput(t);                     // Recursive types put themselves first
    //  TypeFunPtr tfp = (TypeFunPtr)t;
    //  TV2 ret = get(" ret");
    //  if( tfp._fidxs==BitsFun.FULL        ) return t;
    //  if( tfp._fidxs==BitsFun.FULL.dual() ) return t;
    //  for( int fidx : tfp._fidxs ) {
    //    FunNode fun = FunNode.find_fidx(fidx);
    //    if( fun == null || fun.is_dead() || fun.fptr()==null ) continue; // Stale dead fidx
    //    if( fun.fptr().tvar().is_err() ) throw unimpl();
    //    Type tret = fun.ret()._val;
    //    tret = tret instanceof TypeTuple ? ((TypeTuple)tret).at(REZ_IDX) : tret.oob(Type.SCALAR);
    //    ret.walk_types_in(tmem,tret);
    //  }
    //  return t;
    //}
    //
    //if( is_obj() ) {
    //  fput(t);                // Recursive types need to put themselves first
    //  if( !(t instanceof TypeMemPtr) )  return t;
    //  TypeMemPtr tptr = (TypeMemPtr)(t.simple_ptr()==t ? tmem.sharptr(t) : t);
    //  if( !(tptr._obj instanceof TypeStruct) ) return tptr;
    //  TypeStruct ts = (TypeStruct)tptr._obj; // Always a TypeStruct here
    //  if( _args!=null )
    //    for( String id : _args.keySet() ) {
    //      TypeFld fld = ts.get(id);
    //      get(id).walk_types_in(tmem,fld==null ? Type.XSCALAR : fld._t);
    //    }
    //  return tptr.make_from(ts);
    //}
    //
    //if( isa("Ary") ) {
    //  fput(t);                // Recursive types need to put themselves first
    //  if( !(t instanceof TypeMemPtr) )  return t;
    //  TypeMemPtr tptr = (TypeMemPtr)tmem.sharptr(t);
    //  if( !(tptr._obj instanceof TypeAry) ) return t;
    //  TypeAry tary = (TypeAry)tptr._obj;
    //  TV2 elem = get("elem");
    //  if( elem == null ) return t;
    //  return elem.walk_types_in(tmem,tary.elem());
    //}
    //
    //if( isa("Str") )
    //  return fput(_type.meet(t));
    //
    throw unimpl();
  }
  //// Gather occurs of each TV2, and MEET all the corresponding Types.
  //private Type fput(final Type t) {
  //  T2MAP.merge(this, t, Type::meet);
  //  return t;
  //}

  public Type walk_types_out(Type t, CallEpiNode cepi) {
    assert !is_unified();
    if( t == TypeNil.XSCALAR ) return t;  // No lift possible
    //Type tmap = T2MAP.get(this);
    //if( is_leaf() || is_err() ) { // If never mapped on input, leaf is unbound by input
    //  if( tmap==null ) return t;
    //  push_dep(cepi);           // Re-run apply if this leaf re-maps
    //  return tmap.join(t);
    //}
    //if( is_base() ) return tmap==null ? _type : tmap.join(t);
    //if( is_nil() ) return t; // nil is a function wrapping a leaf which is not-nil
    //if( is_fun() ) return t; // No change, already known as a function (and no TFS in the flow types)
    //if( is_obj() ) {
    //  if( !(t instanceof TypeMemPtr) && tmap!=null )
    //    t = tmap;
    //  if( !(t instanceof TypeMemPtr) )
    //    t = as_flow(false);
    //  TypeMemPtr tmp = (TypeMemPtr)t;
    //  if( tmp._obj==TypeObj.UNUSED ) return t; // No lift possible
    //  TypeStruct ts0 = (TypeStruct)tmp._obj;
    //  long duid = dbl_uid(_uid);
    //  TypeStruct ts = WDUPS.get(duid);
    //  if( ts != null ) ts.set_cyclic();
    //  else {
    //    Type.RECURSIVE_MEET++;
    //    ts = TypeStruct.malloc("",false,false);
    //    for( TypeFld fld : ts0.flds() ) ts.add_fld(fld.malloc_from());
    //    ts.set_hash();
    //    WDUPS.put(duid,ts); // Stop cycles
    //    for( TypeFld fld : ts.flds() ) {
    //      TV2 tv2 = get(fld._fld);
    //      if( tv2 != null )
    //        fld.setX(tv2.walk_types_out(fld._t,cepi));
    //    }
    //    if( --Type.RECURSIVE_MEET == 0 )
    //      // Shrink / remove cycle dups.  Might make new (smaller)
    //      // TypeStructs, so keep RECURSIVE_MEET enabled.
    //      ts = ts.install();
    //  }
    //  return tmp.make_from(ts);
    //}
    //if( is_ary() ) {
    //  if( !(t instanceof TypeMemPtr) && tmap!=null )
    //    t = tmap;
    //  if( !(t instanceof TypeMemPtr) )
    //    t = as_flow(false);
    //  TypeMemPtr tmp = (TypeMemPtr)t;
    //  if( tmp._obj==TypeObj.UNUSED ) return t; // No lift possible
    //  TypeAry ta0 = (TypeAry)tmp._obj;
    //  // TODO: Needs the cycle treatment like Structs
    //  TV2 tvlen = get("len" );
    //  TypeInt len = tvlen==null ? TypeInt.INT64 : (TypeInt)tvlen.walk_types_out(ta0._size,cepi);
    //  TV2 tvelem = get("elem" );
    //  Type elem = tvelem==null ? Type.SCALAR : tvelem.walk_types_out(ta0.elem(),cepi);
    //  TypeAry tary = TypeAry.make(len,elem,ta0.stor());
    //  return tmp.make_from(tary);
    //}
    //if( is_str() ) return tmap==null ? _type : tmap.join(t);
    throw unimpl();
  }

  // -----------------
  // Recursively clear _is_copy, through cyclic types.
  static final VBitSet UPDATE_VISIT  = new VBitSet();
  public TV2 clr_cp() { UPDATE_VISIT.clear(); _clr_cp(); return this;}
  private void _clr_cp() {
    TV2 ret;
    if( !_is_copy || UPDATE_VISIT.tset(_uid) ) return;
    _is_copy = false;
    if( _deps!=null ) {
      add_deps_flow();
      for( Node n : _deps.values() )
        if( n instanceof FunPtrNode fptr && fptr.tvar().arg("ret")==this )
      //    for( Apply apply : lam._applys )
      //      if( (ret=apply._fun.find().arg("ret"))!=null )
      //        ret._clr_cp();
          throw unimpl();
    }
    if( _args != null )
      for( TV2 t2 : _args.values() )
        t2._clr_cp();
  }

  // --------------------------------------------
  // This is a TV2 function that is the target of 'fresh', i.e., this function
  // might be fresh-unified with some other function.  Push the application
  // down the function parts; if any changes the fresh-application may make
  // progress.
  static final VBitSet DEPS_VISIT  = new VBitSet();
  public void push_deps( UQNodes deps) { if( deps!=null ) for( Node dep : deps.values() ) push_dep(dep);}
  public void push_dep( Node dep ) {
    assert DEPS_VISIT.isEmpty();
    _push_dep(dep);
    DEPS_VISIT.clear();
  }
  private void _push_dep(Node dep) {
    assert !is_unified();
    if( DEPS_VISIT.tset(_uid) ) return;
    if( _deps!=null ) {
      if( _deps.get(dep._uid)!=null ) return; // Already here and in all children
      _deps = _deps.add(dep);
    } else {
      _deps = UQNodes.make(dep);
    }
    if( _args!=null )
      for( TV2 arg : _args.values() ) // Structural recursion on a complex TV2
        arg.find()._push_dep(dep);
  }

  // Recursively add-deps to worklist
  public void add_deps_flow( ) { assert DEPS_VISIT.isEmpty(); add_deps_flow_impl(); DEPS_VISIT.clear(); }
  private void add_deps_flow_impl( ) {
    Env.GVN.add_flow(_deps);
    // TODO: Lambda "applys" from HM
    if( DEPS_VISIT.tset(_uid) ) return;
    if( _args != null )
      for( TV2 tv2 : _args.values() )
        tv2.add_deps_flow_impl();
  }

  // Merge Dependent Node lists, 'this' into 'that'.  Required to trigger
  // CEPI.unify_lift when types change structurally, or when structures are
  // unifing on field names.
  private void merge_deps( TV2 that ) { that.push_deps(_deps); }

  // --------------------------------------------
  // Pretty print

  // Look for dups, in a tree or even a forest (which Syntax.p() does)
  public VBitSet get_dups() { return _get_dups(new VBitSet(),new VBitSet()); }
  public VBitSet _get_dups(VBitSet visit, VBitSet dups) {
    if( visit.tset(_uid) ) {
      dups.set(debug_find()._uid);
    } else {
      if( _args!=null )
        for( String key : _args.keySet() )
          if( !key.equals("??") )
            _args.get(key)._get_dups(visit,dups);
    }
    return dups;
  }

  // Fancy print for Debuggers - includes explicit U-F re-direction.
  // If debug is on, does NOT roll-up - no side effects.
  @Override public String toString() { return str(new SB(), new VBitSet(), get_dups(), true ).toString(); }
  public String p() { VCNT=0; VNAMES.clear(); return str(new SB(), new VBitSet(), get_dups(), false ).toString(); }
  public SB str(SB sb) { VCNT=0; VNAMES.clear(); return str(sb, new VBitSet(), get_dups(), false ); }
  private static int VCNT;
  private static final HashMap<TV2,String> VNAMES = new HashMap<>();

  // Fancy print for Debuggers - includes explicit U-F re-direction.
  // Does NOT roll-up U-F, has no side-effects.
  public SB str(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
    boolean dup = dups.get(_uid);
    if( !debug && is_unified() ) return find().str(sb,visit,dups,debug);
    if( debug && !_is_copy ) sb.p('%');

    if( is_unified() || (is_leaf() && _err==null) ) {
      vname(sb,debug);
      return is_unified() ? _args.get(">>").str(sb.p(">>"), visit, dups, debug) : sb;
    }

    // Dup printing for all but bases (which are short, just repeat them)
    if( debug || !is_base() || is_err() ) {
      if( dup ) vname(sb,debug);
      if( visit.tset(_uid) && dup ) return sb;
      if( dup ) sb.p(':');
    }

    // Special printing for errors
    if( is_err() ) {
      if( is_err2() ) {
        sb.p("Cannot unify ");
        if( is_fun () ) str_fun (sb,visit,dups,debug).p(" and ");
        if( is_base() ) str_base(sb)                 .p(" and ");
        if( is_ptr () ) str_ptr (sb,visit,dups,debug).p(" and ");
        if( _eflow!=null) sb.p(_eflow)               .p(" and ");
        if( is_obj () ) str_obj (sb,visit,dups,debug).p(" and ");
        return sb.unchar(5);
      }
      return sb.p(_err);      // Just a simple error
    }

    if( is_base() ) return str_base(sb);
    if( is_ptr () ) return str_ptr(sb,visit,dups,debug);
    if( is_obj () ) return str_obj(sb,visit,dups,debug);
    if( is_fun () ) return str_fun(sb,visit,dups,debug);
    if( is_nil () ) return str0(sb,visit,arg("?"),dups,debug).p('?');

    // Generic structural TV2
    sb.p("( ");
    if( _args!=null )
      for( String s : _args.keySet() )
        str0(sb.p(s).p(':'),visit,_args.get(s),dups,debug).p(" ");
    return sb.unchar().p(")");
  }
  static private SB str0(SB sb, VBitSet visit, TV2 t, VBitSet dups, boolean debug) { return t==null ? sb.p("_") : t.str(sb,visit,dups,debug); }
  private SB str_base(SB sb) { return sb.p(_tflow); }
  private SB str_ptr(SB sb, VBitSet visit, VBitSet dups, boolean debug ) {
    TV2 obj = _args==null ? null : _args.get("*");
    str0(sb.p('*'),visit,obj,dups,debug);
    if( _may_nil ) sb.p('?');
    return sb;
  }
  private SB str_fun(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
    sb.p("{ ");
    for( String fld : sorted_flds() ) {
      if( Util.find(ARGS,fld)!=-1 ) // Just the argnames; skip others happens in error cases
        str0(sb,visit,_args.get(fld),dups,debug).p(' ');
    }
    return str0(sb.p("-> "),visit,_args.get(" ret"),dups,debug).p(" }").p(_may_nil ? "?" : "");
  }

  private SB str_obj(SB sb, VBitSet visit, VBitSet dups, boolean debug) {
    if( is_math() ) return sb.p("@{MATH}");
    // The struct contains a clz field, print as "klazz:@{fields}"
    if( (Util.eq(_clz,"int:") || Util.eq(_clz,"flt:")) && size()==1 )
      return sb.p(_clz).p(arg(" def")._tflow);
    final boolean is_tup = is_tup(); // Distinguish tuple from struct during printing
    sb.p(_clz).p(is_tup ? "(" : "@{");
    if( _args==null ) sb.p("_ ");
    else {
      TV2 def = _get(" def");
      if( def!=null ) str0(sb.p(" def="),visit,def.debug_find(),dups,debug).p(is_tup ? ',' : ';');      
      for( String fld : sorted_flds() ) {
        // Skip fields from functions; happens in error cases when mixing
        // structs and functions
        if( fld.charAt(0)==' ' ) continue;
        str0(is_tup ? sb.p(' ') : sb.p(' ').p(fld).p(" = "),visit,_args.get(fld),dups,debug).p(is_tup ? ',' : ';');
      }
    }
    if( is_open() ) sb.p(" ...,");
    sb.unchar().p(!is_tup ? "}" : ")");
    if( _may_nil ) sb.p("?");
    return sb;
  }

  private void vname( SB sb, boolean debug) {
    String v = VNAMES.get(this);
    if( v==null ) {
      if( false ) ;
      else if( debug && is_unified()) v= "X"+_uid;
      else if( debug && is_leaf() )   v= "V"+_uid;
      else if( (++VCNT)-1+'A' < 'V')  v= "" + (char) ('A' + VCNT - 1);
      else                            v= "V"+_uid;
      VNAMES.put(this,v);       // Remember for next time
    }
    sb.p(v);
  }
  private boolean is_tup() {  return _args==null || _args.isEmpty() || _args.containsKey("0"); }
  boolean is_math() { return is_obj() && _args!=null && _args.containsKey("pi"); }
  private Collection<String> sorted_flds() { return new TreeMap<>(_args).keySet(); }
}
