package com.cliffc.aa.node;

import com.cliffc.aa.Env;
import com.cliffc.aa.GVNGCM;
import com.cliffc.aa.Parse;
import com.cliffc.aa.type.*;

// Assert the matching type.  Parse-time error if it does not remove.  Note the
// difference with CastNode: both Nodes always join their input with their
// constant but a TypeNode has to be proven useless and removed before the
// program is type-correct.  A CastNode is always correct from call/return
// semantics, but the join is not-locally-obviously-correct.  The Cast makes it
// locally obvious.
public class TypeNode extends Node {
  private final Type _t;            // Asserted type
  private final Parse _error_parse; // Used for error messages
  public TypeNode( Type t, Node a, Parse P ) { super(OP_TYPE,null,a); _t=t; _error_parse = P; }
  @Override String xstr() { return "assert:"+_t; }
  Node arg() { return in(1); }

  @Override public Node ideal(GVNGCM gvn, int level) {
    Node arg = arg();
    if( gvn.type(arg).isa(_t) ) return arg;
    // If TypeNode check is for a function pointer, it will wrap any incoming
    // function with a new function which does the right arg-checks.  This
    // happens immediately in the Parser and is here to declutter the Parser.
    if( _t instanceof TypeFunPtr/*signature not fidxs*/ ) {
      TypeFunPtr tfp = (TypeFunPtr)_t;
      Type[] targs = tfp._args._ts;
      Node[] args = new Node[targs.length+3];
      FunNode fun = gvn.init((FunNode)(new FunNode(targs).add_def(Env.ALL_CTRL)));
      args[0] = fun;            // Call control
      args[1] = arg;            // Call function is the argument being function-type-checked here
      args[2] = gvn.xform(new ParmNode(-2,"mem",fun,gvn.con(TypeMem.MEM),null));
      Node rpc= gvn.xform(new ParmNode(-1,"rpc",fun,gvn.con(TypeRPC.ALL_CALL),null));
      for( int i=0; i<targs.length; i++ ) {
        // All the parms, with types
        Node parm = gvn.xform(new ParmNode(i,"arg"+i,fun,gvn.con(Type.SCALAR),null));
        args[i+3] = gvn.xform(new TypeNode(targs[i],parm,_error_parse));
      }
      CallNode call = (CallNode)gvn.xform(new CallNode(true,_error_parse,args));
      Node cepi = gvn.xform(new CallEpiNode(call)).keep();
      Node ctl  = gvn.xform(new CProjNode(cepi,0));
      Node postmem = gvn.xform(new MProjNode(cepi,1));
      Node val  = gvn.xform(new  ProjNode(cepi.unhook(),2));
      Node chk  = gvn.xform(new  TypeNode(tfp._ret,val,_error_parse)); // Type-check the return also
      Node premem = args[2];
      Node mem = gvn.xform(new MemMergeNode(premem,postmem,1));
      RetNode ret = (RetNode)gvn.xform(new RetNode(ctl,mem,chk,rpc,fun));
      return gvn.xform(new FunPtrNode(ret));
    }

    // Push TypeNodes 'up' to widen the space they apply to, and hopefully push
    // the type check closer to the source of a conflict.
    Node fun = arg.in(0);
    if( arg instanceof PhiNode &&
        // Not allowed to push up the typing on the unknown arg... because
        // unknown new callers also need the check.

        // TODO: Probably not legit for FunNodes ever, because have to match
        // the CallNode args with the FunNode args.  Or need to add the very
        // same node as the matching call arg.  Alternatively, simplify the
        // double-edge game going on with CG edges.
        //(!(fun instanceof FunNode) || !((FunNode)fun).has_unknown_callers()) ) {
        fun.getClass() == RegionNode.class ) {
      for( int i=1; i<arg._defs._len; i++ )
        gvn.set_def_reg(arg,i,gvn.xform(new TypeNode(_t,arg.in(i),_error_parse)));
      return arg;               // Remove TypeNode, since completely replaced
    }

    return null;
  }
  @Override public Type value(GVNGCM gvn) {
    Node arg = arg();
    Type t = gvn.type(arg);
    return t.isa(_t) ? t.bound(Type.SCALAR) : Type.SCALAR;
  }
  @Override public Type all_type() { return Type.SCALAR; }
  // Check TypeNode for being in-error
  @Override public String err(GVNGCM gvn) { return _error_parse.typerr(gvn.type(arg()),_t); }
}
