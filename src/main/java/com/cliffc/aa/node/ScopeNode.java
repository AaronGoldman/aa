package com.cliffc.aa.node;

import com.cliffc.aa.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Ary;

import java.util.HashMap;
import java.util.function.Predicate;

import static com.cliffc.aa.AA.*;
import static com.cliffc.aa.type.TypeFld.Access;

// Lexical-Scope Node.  Tracks control & phat memory, plus a stack frame (which
// is just a NewNode).  The stack frame maps local variable names to Nodes and
// is modeled as a true memory indirection - for true closures.  Unless there
// is an upwards funarg the stack frame will optimize away.
public class ScopeNode extends Node {

  // Mapping from type-variables to Types.  Types have a scope lifetime like values.
  private final HashMap<String,ConTypeNode> _types; // user-typing type names
  private Ary<IfScope> _ifs;                 // Nested set of IF-exprs used to track balanced new-refs

  public ScopeNode(boolean closure) {
    super(OP_SCOPE,null,null,null,null);
    if( closure ) { add_def(null); add_def(null); add_def(null); } // Wire up an early-function-exit path
    _types = new HashMap<>();
    _ifs = null;
    keep();
  }
  public ScopeNode(HashMap<String,ConTypeNode> types,  Node ctl, Node mem, Node ptr, Node rez) {
    super(OP_SCOPE,ctl,mem,ptr,rez);
    _types = types;
    keep();
  }

  public   Node  ctrl() { return in(CTL_IDX); }
  public   Node  mem () { return in(MEM_IDX); }
  public   Node  ptr () { return in(DSP_IDX); }
  public   Node  rez () { return in(ARG_IDX); }
  public NewObjNode stk () { return (NewObjNode)ptr().in(0); }
  public <N extends Node> N set_ctrl( N n ) { set_def(CTL_IDX,n); return n; }
  public void set_ptr ( Node n) { set_def(DSP_IDX,n); }
  public void set_rez ( Node n) { set_def(ARG_IDX,n); }

  // Set a new deactive GVNd memory, ready for nested Node.ideal() calls.
  public Node set_mem( Node n) {
    assert n._val instanceof TypeMem || n._val ==Type.ANY || n._val ==Type.ALL;
    Node old = mem();
    set_def(MEM_IDX,n);
    if( old!=null ) {
      Env.GVN.add_work_all(old);
      if( old._uses._len <= 2 ) // DEFMEM and a MemSplit
        for( Node use : old._uses )
          if( use instanceof MemSplitNode )
            Env.GVN.add_mono(((MemSplitNode)use).join());
      old.add_work_def_extra(Env.GVN._work_flow,n); // old loses a use, triggers extra
      Env.GVN.add_work_all(n);
    }
    return this;
  }
  public void replace_mem(Node st) {
    set_def(MEM_IDX,null);
    Node mem = Env.GVN.xform(st);
    set_def(MEM_IDX,mem);
  }
  @Override public boolean is_mem() { return true; }

  public Node get(String name) { return stk().get(name); }
  public boolean is_mutable(String name) { return stk().is_mutable(name); }

  public RegionNode early_ctrl() { return (RegionNode)in(4); }
  public    PhiNode early_mem () { return (   PhiNode)in(5); }
  public    PhiNode early_val () { return (   PhiNode)in(6); }
  public void       early_kill() { set_def(4,null); set_def(5,null); set_def(6,null); }
  static final int RET_IDX = 7;

  // Name to type lookup, or null
  public ConTypeNode get_type(String name) { return _types.get(name);  }

  // Alias to TypeMemPtr lookup, or null
  public ConTypeNode get_type(int alias) {
    for( ConTypeNode ctn : _types.values() )
      if( ctn._t instanceof TypeMemPtr &&
          ((TypeMemPtr)ctn._t)._aliases.test(alias) )
        return ctn;
    return null;
  }

  // Extend the current Scope with a new type; cannot override existing name.
  public void add_type( String name, ConTypeNode t ) {
    assert _types.get(name)==null;
    _types.put( name, t );
    add_def(t);                 // Hook constant so it does not die
  }

  // If inputs 4,5,6 are early_ctrl, _mem and _val then you are a closure.
  public boolean is_closure() {
    if( len() < 7 ) return false;
    return !(in(4) instanceof RetNode);
  }

  @Override public Node ideal_reduce() {
    Node ctrl = in(0).is_copy(0);
    if( ctrl != null ) { set_ctrl(ctrl); return this; }

    Node mem = mem();
    Node rez = rez();
    Type trez = rez==null ? null : rez._val;
    if( this!=Env.SCP_0 &&      // Not the top-level, which is always alive
        Env.GVN._opt_mode != GVNGCM.Mode.Parse &&   // Past parsing
        //rez != null &&          // Have a return result
        // And not already wiped it out
        !(mem instanceof ConNode && mem._val ==TypeMem.ANYMEM) &&
        // And used memory is dead
        compute_live_mem(this,mem,rez)==TypeMem.ANYMEM ) {
      // All default mem-Parms of escaping functions update.
      for( Node use : _uses )
        if( use instanceof FunNode )
          for( Node parm : use._uses )
            if( parm instanceof ParmNode && ((ParmNode)parm)._idx==MEM_IDX )
              Env.GVN.add_flow(parm);
      // Wipe out return memory
      return set_mem(Node.con(TypeMem.ANYMEM));
    }

    // If the result is never a function
    int progress = _defs._len;
    if( Env.GVN._opt_mode._CG &&
        !TypeFunPtr.GENERIC_FUNPTR.dual().isa(trez) || trez==Type.XNIL )
      // Wipe out extra function edges.  They are there to act "as if" the
      // exit-scope calls them; effectively an extra wired call use with the
      // most conservative caller.
      while( _defs._len > RET_IDX ) pop();
    // If the result is a function, wipe out wrong fidxs
    if( Env.GVN._opt_mode._CG &&
        trez instanceof TypeFunPtr ) {
      BitsFun fidxs = ((TypeFunPtr)trez)._fidxs;
      for( int i=RET_IDX; i<_defs._len; i++ )
        if( !fidxs.test(((RetNode)in(i))._fidx) )
          remove(i--);
    }

    return progress == _defs._len ? null : this;
  }

  // Produce a tuple of the return result and a summary of all escaping
  // functions, on behalf of the following CEProjs.
  @Override public Type value(GVNGCM.Mode opt_mode) { return Type.CTRL; }

  @Override public TypeMem all_live() { return TypeMem.ALLMEM; }
  @Override public void add_work_use_extra(Work work, Node chg) {
    if( chg==rez() ) {          // If the result changed
      for( Node use : _uses ) {
        if( use != this ) work.add(use);
        if( use instanceof FunNode ) // If escaping functions, their parms now take the default path
          work.add(use._uses);
      }
    }
    if( chg==mem() )            // If the memory changed
      for( Node use : _uses )
        if( use instanceof FunNode ) // If escaping functions, their parms now take the default path
          work.add(((FunNode)use).parm(MEM_IDX));
  }

  // From a memory and a possible pointer-to-memory, find all the reachable
  // aliases and fold them into 'live'.  This is unlike other live_use
  // because this "turns around" the incoming live memory to also be the
  // demanded/used memory.
  static TypeMem compute_live_mem(ScopeNode scope, Node mem, Node rez) {
    Type tmem = mem._val;
    Type trez = rez._val;
    if( !(tmem instanceof TypeMem ) ) return tmem.oob(TypeMem.ALLMEM); // Not a memory?
    TypeMem tmem0 = (TypeMem)tmem;
    if( TypeMemPtr.OOP.isa(trez) ) return tmem0.flatten_fields(); // All possible pointers, so all memory is alive
    // For function pointers, all memory returnable from any function is live.
    if( trez instanceof TypeFunPtr && scope != null ) {
      BitsFun fidxs = ((TypeFunPtr)trez)._fidxs;
      Type disp = ((TypeFunPtr)trez)._dsp;
      BitsAlias esc_in = disp instanceof TypeMemPtr ? ((TypeMemPtr)disp)._aliases : BitsAlias.EMPTY;
      TypeMem tmem3 = TypeMem.ANYMEM;
      for( int i=ARG_IDX+1 ; i<scope._defs._len; i++ ) {
        if( !(scope.in(i) instanceof RetNode) ) continue;
        RetNode ret = (RetNode)scope.in(i);
        int fidx = ret.fidx();
        if( ret._val instanceof TypeTuple && fidxs.test(fidx) ) {
          TypeTuple tret = (TypeTuple)ret._val;
          TypeMem post_call = (TypeMem)tret.at(MEM_IDX);
          Type trez2 = tret.at(REZ_IDX);
          TypeMem cepi_out = CallEpiNode.live_out(tmem0, post_call, trez2, esc_in, null);
          tmem3 = (TypeMem)tmem3.meet(cepi_out);
        }
      }
      return tmem3.flatten_fields();
    }
    if( !(trez instanceof TypeMemPtr) ) return TypeMem.ANYMEM; // Not a pointer, basic live only
    if( trez.above_center() ) return TypeMem.ANYMEM; // Have infinite choices still, report basic live only
    // Find everything reachable from the pointer and memory, and report it all
    BitsAlias aliases = tmem0.all_reaching_aliases(((TypeMemPtr)trez)._aliases);
    return tmem0.slice_reaching_aliases(aliases).flatten_fields();
  }

  @Override public TypeMem live(GVNGCM.Mode opt_mode) {
    // Primitive scope does not demand any memory
    if( this==Env.SCP_0 )  return TypeMem.ANYMEM;
    if( opt_mode == GVNGCM.Mode.Parse ) return TypeMem.MEM;
    // All fields in all reachable pointers from rez() will be marked live
    return compute_live_mem(this,mem(),rez());
  }

  @Override public TypeMem live_use(GVNGCM.Mode opt_mode, Node def ) {
    // Basic liveness ("You are Alive!") for control and returned value
    if( def == ctrl() ) return TypeMem.ALIVE;
    if( def == rez () ) return def.all_live().basic_live() ? TypeMem.LIVE_BOT : TypeMem.ANYMEM;
    // Returned display is dead in a whole program.
    // In a partial-program, what's in the display is exported to the next step.
    if( def == ptr () ) return opt_mode._CG ? TypeMem.DEAD : TypeMem.LIVE_BOT; // Returned display is dead after CG
    // Memory returns the compute_live_mem state in _live.  If rez() is a
    // pointer, this will include the memory slice.
    if( def == mem() )
      return opt_mode==GVNGCM.Mode.Parse ? TypeMem.ALLMEM : _live.flatten_fields();
    // Top-level function pointer escape
    if( def instanceof RetNode )
      return _live;
    // Merging exit path
    return def._live;
  }

  // Set of top-level escaping functions
  private Type _escache_trez, _escache_tmem; // Small cache has a 50% hit rate
  private BitsFun _escache_escs;
  public BitsFun top_escapes() {
    if( _val.above_center() ) return BitsFun.EMPTY;
    if( is_prim() ) return BitsFun.FULL; // All the primitives escape
    if( !Env.GVN._opt_mode._CG ) return BitsFun.FULL; // Not run Opto yet
    Type trez = rez()._val;
    Type tmem = mem()._val;
    if( _escache_trez == trez &&  _escache_tmem == tmem ) return _escache_escs; // Cache hit
    // Cache miss, compute the hard way
    if( trez.above_center() )
      if( Combo.HM_IS_HIGH ) return BitsFun.EMPTY;
      else if( trez instanceof TypeFunPtr )
        trez = trez.dual();   // Bring the escaping unresolved functions down low
    if( TypeFunPtr.GENERIC_FUNPTR.isa(trez) ) return BitsFun.FULL; // Can lift to any function
    tmem = tmem instanceof TypeMem ? (TypeMem)tmem : tmem.oob(TypeMem.ALLMEM);
    BitsFun fidxs = trez.all_reaching_fidxs((TypeMem)tmem);
    _escache_trez = trez;
    _escache_tmem = tmem;
    _escache_escs = fidxs;
    return fidxs;
  }

  // GCP discovers functions which escape at the top-most level, and wires the
  // RetNode to the top-most Scope to mimic future unknown callers.
  void check_and_wire() {
    if( this==Env.SCP_0 ) return; // Do not wire the escaping primitives?
    BitsFun escs = top_escapes();
    if( escs==BitsFun.FULL ) return; // Error exit
    for( int fidx : escs ) {
      boolean found=false;
      for( int i=ARG_IDX+1; i<len(); i++ )
        if( in(i) instanceof RetNode && ((RetNode)in(i))._fidx==fidx )
          {found=true; break; };
      if( !found )
        add_def(FunNode.find_fidx(fidx).ret());
    }
  }


  @Override public int hashCode() { return 123456789; }
  // ScopeNodes are never equal
  @Override public boolean equals(Object o) { return this==o; }
  // End of dominator tree; do not walk past
  @Override Node walk_dom_last(Predicate<Node> P) { return P.test(this) ? this : null; }

  // Create an if-scope and start tracking vars
  public void push_if() {
    if( _ifs == null ) _ifs = new Ary<>(new IfScope[1],0);
    _ifs.push(new IfScope());
  }
  // Flip true-arm for false-arm
  public void flip_if() { _ifs.last().flip(); }

  // Pop ifscope
  public void pop_if() { _ifs.pop(); }

  // Test for being inside a ?: expression
  public boolean test_if() { return _ifs!=null && !_ifs.isEmpty(); }

  public void def_if( String name, Access mutable, boolean create ) {
    if( _ifs == null || _ifs._len==0 ) return; // Not inside an If
    _ifs.last().def(name,mutable,create);      // Var defined in arm of if
  }

  public Node check_if( boolean arm, Parse bad, GVNGCM gvn, Node ctrl, Node mem ) { return _ifs.last().check(this,arm,bad,gvn,ctrl,mem); }

  private static class IfScope {
    HashMap<String,Access> _tvars, _fvars;
    boolean _arm = true;
    void flip() { _arm = !_arm; }
    // Record creation on either arm of an if
    void def(String name, Access mutable, boolean create) {
      if( _tvars == null ) { _tvars = new HashMap<>(); _fvars = new HashMap<>(); }
      // If there was no prior create inside the same if, then this update
      // predates the if and is not involved in a partial-creation error
      if( !create && !_arm && _tvars.get(name) != null )
        _fvars.put(name,mutable);
      if( create ) {
        HashMap<String,Access> vars = _arm ? _tvars : _fvars;
        Access res = vars.put(name,mutable);
        assert res==null;         // No double-creation
      }
    }
    // Check for balanced creation, and insert errors on unbalanced
    Node check(ScopeNode scope, boolean arm, Parse bad, GVNGCM gvn, Node ctrl, Node mem) {
      if( _tvars == null ) return mem; // No new vars on either arm
      // Pull from both variable sets names that are common to both
      if( arm ) {               // Only do it first time
        Ary<String> names = new Ary<>(String.class).addAll(_tvars.keySet());
        while( !names.isEmpty() ) {
          String name = names.pop();
          if( _fvars.remove(name)!=null ) _tvars.remove(name);
        }
      }
      // Everything in this set is a partially-created variable error
      HashMap<String,Access> vars = arm ? _fvars : _tvars;
      if( vars.isEmpty() ) return mem;
      mem.unkeep(2);             // Passed-in 'hooked' memory
      for( String name : vars.keySet() ) {
        String msg = "'"+name+"' not defined on "+arm+" arm of trinary";
        Node err = gvn.xform(new ErrNode(ctrl,bad,msg));
        // Exactly like a parser store of an error, on the missing side
        mem = gvn.xform(new StoreNode(mem,scope.ptr(),err,Access.Final,name,bad));
      }
      return mem.keep(2);        // Return 'hooked' memory
    }
  }
}
