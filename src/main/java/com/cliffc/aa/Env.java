package com.cliffc.aa;

import com.cliffc.aa.node.*;
import com.cliffc.aa.tvar.TV3;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.NonBlockingHashMap;
import com.cliffc.aa.util.VBitSet;

import java.util.*;

import static com.cliffc.aa.AA.*;

// An "environment", a lexical Scope tracking mechanism that runs 1-for-1 in
// parallel with a ScopeNode.
//
// TOP: The top-most environment, which includes the primitives (e.g. "+") and top-level types (e.g. "int64")
// TOP._scope: Node placeholder pointing to all the primitives and types, keeping them alive.
// The TOP env is closed when the 'aa' process no longer accepts more code.

// FILE: The next env down; the results of a single complete normal parse and typing.
// FILE._scope: Result of said parse.

// The FILE level env is used to hold the results of a single file parse, or a
// single test (but not a collection of tests in the same test function), or a
// single REPL session (but not a single REPL line).

// Testing generally starts with TOP, then parses whole complete (short)
// programs at a FILE env and checks their typing - within the same TOP scope.
// A single testParseXX() function will have many tests but only one TOP.

// The REPL starts with a TOP, then opens a FILE level env - then opens a
// single-line Env, parses and types it, then exports it to the FILE env.

public class Env implements AutoCloseable {
  public static Env TOP,FILE;
  public static final GVNGCM GVN = new GVNGCM(); // Initial GVN

  // KeepNode represents future un-parsed users of a Node.  Removed after parsing.
  // Semantically, it represents a conservative approximation of ALL uses.
  public static final KeepNode KEEP_ALIVE = new KeepNode();

  // The Root Node.  Program start and exit; the Universe betwixt the end and the beginning
  public static final   RootNode ROOT;
  // Program start control and memory.  NOT module-start, this presumes NO prior state.
  public static final  CProjNode CTL_0; // Program start value control
  public static final  MProjNode MEM_0; // Program start value memory

  // Short-cuts for common constants Nodes.
  public static final ConNode ANY;   // Common ANY / used for dead
  public static final ConNode ALL;   // Common ALL / used for errors
  public static final ConNode XCTRL; // Always dead control
  public static final ConNode XNIL;  // Default 0
  public static final ConNode  NIL;  // Default 0
  public static final ConNode XSCALAR;// Default scalar
  public static final ConNode THUNK; // Default thunk parameter
  public static final ConNode UNUSED;// Dead alias
  public static final ConNode ALLMEM;//   Used whole memory
  public static final ConNode   XMEM;// Unused whole memory

  // All possible return addresses (RPCs).
  public static final ProjNode ALL_CALL;
  // All possible escaped *internal* aliases and fidxs
  public static final ProjNode ALL_ESC;

  // Initial program state.  Includes definitions for int: and flt: clazzes,
  // which includes most common primitives.
  public static final StructNode STK_0; // Program start stack frame (has primitives)
  public static final  ScopeNode SCP_0; // Program start scope

  // Global named types.  Type names are ALSO lexically scoped during parsing
  // (dictates visibility of a name).  During semantic analysis a named type
  // can be Loaded from as a class obj, requiring Loads reverse the type name
  // to the prototype obj.
  public static final NonBlockingHashMap<String,StructNode> PROTOS;

  // Add a permanent edge use to all these Nodes, keeping them alive forever.
  @SuppressWarnings("unchecked")
  private static <N extends Node> N keep(N n) {
    N xn = GVN.init(n);
    KEEP_ALIVE.add_def(xn);
    return xn;
  }

  static {
    // Top-level or common default values

    // Common constants
    ANY   = keep(new ConNode<>(Type.ANY   ));
    ALL   = keep(new ConNode<>(Type.ALL   ));
    XCTRL = keep(new ConNode<>(Type.XCTRL ));
    XNIL  = keep(new ConNode<>(TypeNil.XNIL));
    NIL   = keep(new ConNode<>(TypeNil. NIL));
    XSCALAR=keep(new ConNode<>(TypeNil.XSCALAR));
    THUNK = keep(new ConNode<>(TypeFunPtr.THUNK));
    UNUSED= keep(new ConNode<>(TypeStruct.UNUSED));
    ALLMEM= keep(new ConNode<>(TypeMem.ALLMEM));
    XMEM  = keep(new ConNode<>(TypeMem.EXTMEM));

    // The Universe outside the parse program
    ROOT  = keep(new RootNode());
    // Initial control & memory
    CTL_0 = keep(new CProjNode(ROOT,0));
    MEM_0 = keep(new MProjNode(ROOT,MEM_IDX));

    // All the Calls in the Universe, which might call somebody.
    ALL_CALL=keep(new ProjNode(ROOT,2));

    // All the escaped fidxs and aliases, which might called or modified
    ALL_ESC = keep(new ProjNode(ROOT,3));
    
    PROTOS = new NonBlockingHashMap<>();

    // The Top-Level environment; holds the primitives.
    TOP = new Env();
    SCP_0 = TOP._scope;
    STK_0 = SCP_0.stk();
    PrimNode.PRIMS();           // Initialize
    record_for_reset();         // Record for reset between tests
  }


  final public Env _par;         // Parent environment
  public final ScopeNode _scope; // Lexical anchor; "end of display"; goes when this environment leaves scope
  public final FunNode _fun;     // Matching FunNode for this lexical environment

  // Shared Env constructor.
  Env( Env par, FunNode fun, int nargs, Node ctrl, Node mem, Node dsp_ptr, StructNode fref ) {
    _par = par;
    _fun = fun;
    StructNode dsp = fref==null ? new StructNode(nargs,false,null, "", Type.ALL).init() : fref;
    dsp.add_fld("^",TypeFld.Access.Final,dsp_ptr,null);
    NewNode ptr = new NewNode();  GVN.add_flow(ptr);
    mem = new StoreNode(mem,ptr,dsp,null).init();
    mem.in(1).xliv();
    // Install a top-level prototype mapping
    if( fref!=null ) {          // Forward ref?
      //String fname = fref._ts._name;
      //assert !PROTOS.containsKey(fname); // All top-level type names are globally unique
      //PROTOS.put(fname,dsp);
      throw unimpl();
    }
    HashMap<String,TypeNil> types = new HashMap<>();
    _scope = new ScopeNode(types,ctrl,mem,XNIL,ptr,dsp).init();
    if( _par==null )
      Type.init0(types);
    KEEP_ALIVE.add_def(_scope);
    GVN.iter();
  }

  // Top-level Env.  Contains, e.g. the primitives.
  // Above any file-scope level Env.
  private Env( ) { this(null,null,0,CTL_0,MEM_0,XNIL,null); }

  // Gather and report errors and typing
  TypeEnv gather_errors(ErrMsg err) {
    // Hunt for typing errors in the alive code
    HashSet<ErrMsg> errs = new HashSet<>();
    if( err!= null ) errs.add(err);
    VBitSet bs = new VBitSet();
    Env.ROOT.walkerr_def(errs,bs);
    ArrayList<ErrMsg> errs0 = new ArrayList<>(errs);
    Collections.sort(errs0);

    Node rez = Env.ROOT.in(REZ_IDX);
    Type mem = Env.ROOT.in(MEM_IDX)._val;
    Type val = rez._val;   
    BitsAlias aliases = Env.ROOT.ralias();
    BitsFun   fidxs   = Env.ROOT.rfidxs();
    return new TypeEnv(val,     // GCP result
                       fidxs,   // Escaping FIDXS
                       aliases, // Escaping ALIASES
                       mem instanceof TypeMem ? (TypeMem)mem : mem.oob(TypeMem.ALLMEM),
                       AA.DO_HMT && rez.has_tvar() ? rez.tvar() : null,
                       errs0.isEmpty() ? null : errs0);
  }



  // Promote any forward refs in this display to an outer scope.
  // Close the currently open display, and remove its alias from the set of
  // active display aliases (which are otherwise available to all function
  // definitions getting parsed).
  @Override public void close() {
    // Promote forward refs to the next outer scope
    StructNode stk = _scope.stk();
    assert stk.is_closed();
    ScopeNode pscope = _par._scope;
    stk.promote_forward(pscope.stk());
    for( String tname : _scope.typeNames() ) {
      TypeNil t = _scope.get_type(tname);
      //if( t.is_forward_type() )
      //  pscope.add_type(tname,t);
      throw unimpl();
    }

    Node xscope = KEEP_ALIVE.pop();// Unhook scope
    assert _scope==xscope;
    GVN.add_flow_defs(_scope);  // Recompute liveness of scope inputs
    GVN.iter();
  }

  // Wire up an early function exit.  Hunts through all scopes until it finds a closure.
  Node early_exit( Parse P, Node val ) {
    return _scope.stk().is_closure() ? P.do_exit(_scope,val) : _par.early_exit(P,val); // Hunt for an early-exit-enabled scope
  }

  // Record global static state for reset
  private static void record_for_reset() {
    Node.init0(); // Record end of primitives
    GVN.init0();
    FunNode.init0();
    BitsAlias.init0();
    BitsFun  .init0();
    BitsRPC  .init0();
  }

  // Reset all global statics for the next parse.  Useful during testing when
  // many top-level parses happen in a row.
  public static void top_reset() {
    ROOT.reset();
    TV3.reset_to_init0();
    Node.VALS.clear();          // Clean out hashtable
    GVN.work_clear();
    VBitSet visit = new VBitSet();
    ROOT.walk_reset(visit);          // Clean out any wired prim calls
    KEEP_ALIVE.walk_reset(visit);    // Clean out any wired prim calls
    GVNGCM.KEEP_ALIVE.walk_reset(visit);
    Combo.reset();
    GVN.iter();                 // Clean out any dead; reset prim types
    for( Node n : Node.VALS.keySet() ) // Assert no leftover bits from the prior compilation
      assert n._uid < Node._INIT0_CNT; //
    AA.reset();
    Node      .reset_to_init0();
    CallNode  .reset_to_init0();
    GVN       .reset_to_init0();
    FunNode   .reset_to_init0();
    NewNode   .reset_to_init0();
    BitsAlias .reset_to_init0();
    BitsFun   .reset_to_init0();
    BitsRPC   .reset_to_init0();
  }

  // Return Scope for a name, so can be used to determine e.g. mutability
  ScopeNode lookup_scope( String name, boolean lookup_current_scope_only ) {
    if( name == null ) return null; // Handle null here, easier on parser
    if( _scope.stk().find(name)!= -1 ) return _scope;
    return _par == null || lookup_current_scope_only ? null : _par.lookup_scope(name,false);
  }

  // Name lookup is the same for all variables, including function defs (which
  // are literally assigning a lambda to a ref).  Only returns nodes registered
  // with GVN.
  public Node lookup( String name ) {
    ScopeNode scope = lookup_scope(name,false);
    return scope==null ? null : scope.get(name);
  }

  // Type lookup in any scope
  Type lookup_type( String tvar ) {
    Type t = _scope.get_type(tvar);
    if( t != null ) return t;
    return _par == null ? null : _par.lookup_type(tvar);
  }
  // Update type name token to type mapping in the current scope
  void add_type( String name, TypeNil t ) { _scope.add_type(name,t); }

  // Test for being inside a ?: expression
  boolean test_if() {
    if( _scope.test_if() ) return true;
    if( _scope.stk().is_closure() || _par==null ) return false;
    return _par.test_if();
  }
}
