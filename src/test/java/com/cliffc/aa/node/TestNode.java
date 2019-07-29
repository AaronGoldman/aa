package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.NonBlockingHashMapLong;
import com.cliffc.aa.util.Util;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;

public class TestNode {
  // A private set of input nodes to feed into each tested Node - a hand-rolled
  // sub-graph.
  private Node[] _ins;
  // A private GVN for computing value() calls.
  private GVNGCM _gvn;

  // A sparse list of all subtypes.  The outer array is the index into
  // Type.ALL_TYPES(), and the inner array is the set of immediate sub-Types
  // (again as indices into ALL_TYPES).  Numbers are sorted.
  private int[][] _subtypes;

  // Build a minimal spanning sub-Type tree from the set of sample types.
  // We'll use this to know which other types sub-Type this type... and thus be
  // more efficient in testing all Types which subtype another Type.  The outer
  // array is the index into Type.ALL_TYPES(), and the inner array is the set
  // of immediate sub-Types (again as indices into ALL_TYPES).
  private int[][] _min_subtypes;

  private NonBlockingHashMapLong<Type> _values;

  // Array doubling of longs
  private long[] _work = new long[1];
  private int _work_len;

  // Worse-case output for a Node
  private Type _alltype;

  private int _errs;

  // temp/junk holder for "instant" junits, when debugged moved into other tests
  @Test public void testNode() {
    Type.init0(new HashMap<>());
    Env.top();
  }

  // A sparse list of all subtypes.  The outer array is the index into
  // Type.ALL_TYPES(), and the inner array is the set of immediate sub-Types
  // (again as indices into ALL_TYPES).  ALL_TYPES is sorted by 'isa'.  Numbers
  // in subs[i] are sorted and always greater than 'i'.
  private static int[][] make_subtypes(Type[] alltypes) {
    int[][] subs = new int[alltypes.length][];
    int[] tmp = new int[alltypes.length];
    for( int i=0; i<subs.length; i++ ) {
      int len=0;
      for( int j=0; j<subs.length; j++ )
        if( i!=j && alltypes[i].isa(alltypes[j]) )
          tmp[len++]=j;         // isa numbers are sorted by increasing 'j'
      subs[i] = Arrays.copyOfRange(tmp,0,len);
    }
    return subs;
  }

  // Build a minimal subtype graph from the set of sample types.  We'll use
  // this to know which other types sub-Type this type... and thus be more
  // efficient in testing all Types which subtype another Type.  The outer
  // array is the index into Type.ALL_TYPES(), and the inner array is the set
  // of immediate sub-Types (again as indices into ALL_TYPES).
  private int[][] make_minimal_graph() {

    int[][] subs = new int[_subtypes.length][];
    for( int i=0; i<subs.length; i++ )
      subs[i] = _subtypes[i].clone();

    // For all types
    for( int i=0; i<subs.length; i++ ) {
      int[] subis = subs[i];
      // For all 'i' subtypes
      for( int j=0; j<subis.length && subis[j] != -1; j++ ) {
        int[] subjs = subs[subis[j]];
        // Pull out of subis all things found in subjs.  We have a subtype isa
        // path from i->j by design of _subtypes, and the j->k list in subjs.
        // Remove any i->k as being redundant.
        int ix = j+1, ixx = j+1; // Index to read, index to keep non-dups
        int jx = 0; // Index to read the k's
        while( ix<subis.length && jx<subjs.length ) {
          int six = subis[ix];
          int sjx = subjs[jx];
          assert sjx != -1;
          if( six==-1 ) break; // Hit end-of-array sentinel
          if( six==sjx ) { ix++; jx++; } // i->j and j->sjx and i->sjx, skip both forward
          else if( six < sjx ) subis[ixx++] = subis[ix++]; // Keep and advance
          else jx++;                                       // Advance
        }
        while( ixx < ix ) subis[ixx++] = -1; // Sentinel remaining unused elements
      }
      int ix = Util.find(subs[i],-1);
      if( ix != -1 ) subs[i] = Arrays.copyOfRange(subs[i],0,ix); // Compress extra elements
    }

    return subs;
  }

  private void push( long x ) {
    if( _work_len == _work.length )
      _work = Arrays.copyOf(_work,_work_len<<1);
    _work[_work_len++] = x;
  }

  private long pop() { return _work[--_work_len]; }

  // Print subtypes in RPO
  private void print( int x, int d ) {
    Type dt = _values.get(x);
    if( dt==null ) {
      _values.put(x,dt=TypeInt.con(d));
      int[] subs = _min_subtypes[x];
      for( int sub : subs )
        print(sub,d+1);
      System.out.println("#"+x+" = "+Type.ALL_TYPES()[x]+" "+d+" "+dt.getl());
    } else if( d < dt.getl() ) {
      _values.put(x,TypeInt.con(d));
      System.out.println("Shrink #"+x+" = "+Type.ALL_TYPES()[x]+" "+d+" "+dt.getl());
    }
  }


  // Major test for monotonic behavior from value() calls.  Required to prove
  // correctness & linear-time speed from GCP & a major part of GVN.iter().
  // (for GVN.iter(), ideal() calls ALSO have to be monotonic but using a
  // different metric that is harder to test for).

  // How this works: for all Node.value() calls, for all input types, if the
  // input type changes monotonically, so does the output type.  Many input
  // types are illegal for many Nodes, and can/should be asserted for by the
  // Node.  However, all legal inputs should produce an output with the
  // monotonicity invariant.

  public static void main( String[] args ) { new TestNode().testMonotonic();  }
  @SuppressWarnings("unchecked")
  @Test public void testMonotonic() {
    Type.init0(new HashMap<>());
    Env.top();
    assert _errs == 0;          // Start with no errors
    // All The Types we care to reason about.  There's an infinite number of
    // Types, but mostly are extremely similar - so we limit ourselves to a
    // subset which has at least one of unique subtype, plus some variations
    // inside the more complex Types.
    _subtypes = make_subtypes(Type.ALL_TYPES());

    // Build a minimal spanning sub-Type tree from the set of sample types.
    // We'll use this to know which other types sub-Type this type... and thus be
    // more efficient in testing all Types which subtype another Type.
    _min_subtypes = make_minimal_graph();

    // Per-node-type cached value() results
    _values = new NonBlockingHashMapLong<Type>(8*1000000,false);

    // Print the types and subtypes in a RPO
    //print(0,0);
    //_values.clear(true);

    // Setup to compute a value() call: we need a tiny chunk of Node graph with
    // known inputs.
    _gvn = new GVNGCM();
    _ins = new Node[4];
    _ins[0] = new RegionNode(null,new ConNode<>(Type.CTRL),new ConNode<>(Type.CTRL));
    for( int i=1; i<_ins.length; i++ )
      _ins[i] = new ConNode<>(Type.SCALAR);
    Node mem = new ConNode<Type>(TypeMem.MEM);
    FunNode fun_forward_ref = new FunNode("anon");

    Node unr = Env.top().lookup("+"); // All the "+" functions
    FunNode fun_plus = ((EpilogNode)unr.in(1)).fun();

    TypeMemPtr from_ptr = TypeMemPtr.make(BitsAlias.REC,TypeStruct.POINT);
    TypeMemPtr to_ptr   = TypeMemPtr.make(BitsAlias.REC,TypeName.TEST_STRUCT);

    // Testing 1 set of types into a value call.
    // Comment out when not debugging.
    Type rez = test1jig(new StoreNode(_ins[0],_ins[1],_ins[2],_ins[3],0,null),
                        Type.ANY,TypeMem.MEM_ABC,TypeMemPtr.STRPTR,Type.ALL);
             
    // All the Nodes, all Values, all Types
    test1monotonic(new   CallNode(false,null,_ins[0],  unr  ,mem,_ins[2],_ins[3]));
    test1monotonic(new   CallNode(false,null,_ins[0],_ins[1],mem,_ins[2],_ins[3]));
    test1monotonic(new    ConNode<Type>(          TypeInt.FALSE));
    test1monotonic(new    ConNode<Type>(          TypeStr.ABC  ));
    test1monotonic(new    ConNode<Type>(          TypeFlt.FLT64));
    test1monotonic(new   CastNode(_ins[0],_ins[1],TypeInt.FALSE));
    test1monotonic(new   CastNode(_ins[0],_ins[1],TypeStr.ABC  ));
    test1monotonic(new   CastNode(_ins[0],_ins[1],TypeFlt.FLT64));
    test1monotonic(new  CProjNode(_ins[0],0));
    test1monotonic(new EpilogNode(_ins[0],mem,_ins[1],_ins[2],fun_forward_ref,"unknown_ref"));
    test1monotonic(new EpilogNode(_ins[0],mem,_ins[1],_ins[2],fun_plus,"plus"));
    test1monotonic(new    ErrNode(_ins[0],"\nerr\n",  TypeInt.FALSE));
    test1monotonic(new    ErrNode(_ins[0],"\nerr\n",  TypeStr.ABC  ));
    test1monotonic(new    ErrNode(_ins[0],"\nerr\n",  TypeFlt.FLT64));
    test1monotonic(new    ErrNode(_ins[0],"\nerr\n",  Type   .CTRL ));
    test1monotonic(new    FunNode(new Type[]{TypeInt.INT64}));
    test1monotonic(new     IfNode(_ins[0],_ins[1]));
    for( IntrinsicNewNode prim : IntrinsicNewNode.INTRINSICS )
      test1monotonic_intrinsic(prim);
    test1monotonic(new IntrinsicNode.ConvertPtrTypeName("test",from_ptr,to_ptr,null,_ins[1],_ins[2]));
    test1monotonic(new   LoadNode(_ins[0],_ins[1],_ins[2],0,null));
    test1monotonic(new MemMergeNode(_ins[1],_ins[2]));
    test1monotonic(new    NewNode(new Node[]{null,_ins[1],_ins[2]},TypeStruct.POINT));
    test1monotonic(new    NewNode(new Node[]{null,_ins[1],_ins[2]},TypeName.TEST_STRUCT));
    ((ConNode<Type>)_ins[1])._t = Type.SCALAR; // ParmNode reads this for _alltype
    test1monotonic(new   ParmNode( 1, "x",_ins[0],(ConNode)_ins[1],"badgc"));
    test1monotonic(new    PhiNode("badgc",_ins[0],_ins[1],_ins[2]));
    for( PrimNode prim : PrimNode.PRIMS )
      test1monotonic_prim(prim);
    test1monotonic(new   ProjNode(_ins[0],1));
    test1monotonic(new RegionNode(null,_ins[1],_ins[2]));
    test1monotonic(new  StoreNode(_ins[0],_ins[1],_ins[2],_ins[3],0,null));
    //                  ScopeNode has no inputs, and value() call is monotonic
    //                    TmpNode has no inputs, and value() call is monotonic
    test1monotonic(new   TypeNode(TypeInt.FALSE,_ins[1],null));
    test1monotonic(new   TypeNode(TypeMemPtr.ABCPTR,_ins[1],null));
    test1monotonic(new   TypeNode(TypeFlt.FLT64,_ins[1],null));

    assertEquals(0,_errs);
  }

  @SuppressWarnings("unchecked")
  private Type test1jig(final Node n, Type t0, Type t1, Type t2, Type t3) {
    _alltype = n.all_type();
    assert _alltype.is_con() || (!_alltype.above_center() && _alltype.dual().above_center());
    Type[] all = Type.ALL_TYPES();
    // Prep graph edges
    _gvn.setype(_ins[0],                        t0);
    _gvn.setype(_ins[1],((ConNode)_ins[1])._t = t1);
    _gvn.setype(_ins[2],((ConNode)_ins[2])._t = t2);
    _gvn.setype(_ins[3],((ConNode)_ins[3])._t = t3);
    return n.value(_gvn);
  }
  
  private void test1monotonic(Node n) {
    assert n._defs._len>0;
    test1monotonic_init(n);
  }

  // Fill a Node with {null,edge,edge} and start the search
  private void test1monotonic_prim(PrimNode prim) {
    PrimNode n = (PrimNode)prim.copy(_gvn);
    assert n._defs._len==0;
    n.add_def( null  );
    n.add_def(_ins[1]);
    if( n._targs._ts.length >= 2 ) n.add_def(_ins[2]);
    test1monotonic_init(n);
  }

  // Fill a Node with {null,edge,edge} and start the search
  private void test1monotonic_intrinsic(IntrinsicNewNode prim) {
    IntrinsicNewNode n = prim.copy(_gvn);
    assert n._defs._len==0;
    n.add_def( null  );
    n.add_def(_ins[1]);         // memory
    n.add_def(_ins[2]);         // arg
    if( n._targs._ts.length >= 2 ) n.add_def(_ins[3]);
    test1monotonic_init(n);
  }

  @SuppressWarnings("unchecked")
  private void test1monotonic_init(final Node n) {
    System.out.println(n.xstr());
    _values.clear(true);
    _alltype = n.all_type();
    assert _alltype.is_con() || (!_alltype.above_center() && _alltype.dual().above_center());

    _values.put(0,Type.ANY);    // First args are all ANY, so is result
    push(0);                    // Init worklist
    
    Type[] all = Type.ALL_TYPES();
    long t0 = System.currentTimeMillis();
    long nprobes = 0, nprobes1=0;
    while( _work_len > 0 ) {
      long xx = pop();
      Type vn = get_value_type(xx);
      int x0 = xx(xx,0), x1 = xx(xx,1), x2 = xx(xx,2), x3 = xx(xx,3);
      // Prep graph edges
      _gvn.setype(_ins[0],                        all[x0]);
      _gvn.setype(_ins[1],((ConNode)_ins[1])._t = all[x1]);
      _gvn.setype(_ins[2],((ConNode)_ins[2])._t = all[x2]);
      _gvn.setype(_ins[3],((ConNode)_ins[3])._t = all[x3]);

      // Subtypes in 4 node input directions
      int[] stx0 = stx(n,xx,0);
      for( int y0 : stx0 )
        set_value_type(n, vn, xx, xx(y0,x1,x2,x3), 0, y0, all );
      set_type(0,all[x0]);

      int[] stx1 = stx(n,xx,1);
      for( int y1 : stx1 )
        set_value_type(n, vn, xx, xx(x0,y1,x2,x3), 1, y1, all );
      set_type(1,all[x1]);

      int[] stx2 = stx(n,xx,2);
      for( int y2 : stx2 )
        set_value_type(n, vn, xx, xx(x0,x1,y2,x3), 2, y2, all );
      set_type(2,all[x2]);
      
      int[] stx3 = stx(n,xx,3);
      for( int y3 : stx3 )
        set_value_type(n, vn, xx, xx(x0,x1,x2,y3), 3, y3, all );
      set_type(3,all[x3]);

      nprobes1 += stx0.length+stx1.length+stx2.length+stx3.length;
      long t1 = System.currentTimeMillis();
      if( t1-t0 >= 1000 ) {
        nprobes += nprobes1;
        System.out.println("Did "+nprobes1+" in "+(t1-t0)+"msecs, worklist has "+_work_len+" states, total probes "+nprobes);
        nprobes1=0;
        t0=t1;
      }
    }
  }

  private void set_value_type(Node n, Type vn, long xx, long xxx, int idx, int yx, Type[] all ) {
    Type vm = _values.get(xxx);
    if( vm == null ) {
      set_type(idx,all[yx]);
      vm = n.value(_gvn);
      // Assert the alltype() bounds any value() call result.
      assert vm.isa(_alltype);
      assert _alltype.dual().isa(vm);
      Type old = _values.put(xxx,vm);
      assert old==null;
      push(xxx);            // Now visit all children
    }
    // The major monotonicity assert
    if( vn!= vm && !vn.isa(vm) ) {
      int x0 = xx(xx,0), x1 = xx(xx,1), x2 = xx(xx,2), x3 = xx(xx,3);
      System.out.println(n.xstr()+"("+all[x0]+","+all[x1]+","+all[x2]+","+all[x3]+") = "+vn);
      System.out.println(n.xstr()+"("+all[idx==0?yx:x0]+","+all[idx==1?yx:x1]+","+all[idx==2?yx:x2]+","+all[idx==3?yx:x3]+") = "+vm);
      _errs++;
    }
  }
  @SuppressWarnings("unchecked")
  private void set_type(int idx, Type tyx) {
    if( idx > 0 ) ((ConNode)_ins[idx])._t = tyx;
    _gvn.setype(_ins[idx], tyx);
  }

  private static int[] stx_any = new int[]{};
  private int[] stx(final Node n, long xx, int i) {
    if( i >= n._defs._len || n.in(i) == null ) return stx_any;
    return _min_subtypes[xx(xx,i)];
  }

  // Get the value Type for 4 input types.  Must exist.
  private Type get_value_type(long xx) {
    Type vt = _values.get(xx);
    assert vt!=null;
    return vt;
  }

  private static long xx( int i0, int i1, int i2, int i3 ) {
    return i0+(i1<<8)+(i2<<16)+(i3<<24);
  }
  private static int xx(long xx, int i) { return (int)((xx>>(i<<3)) & 0xffL); }
}
