package com.cliffc.aa;

import com.cliffc.aa.node.Node;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.SB;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

// Hindley-Milner typing.  Complete stand-alone, for research.
// MEETs base types, instead of declaring type error.
// Does standard lexical scoping, which is not needed for SSA form.
public class HM1 {
  public static HMType HM(Syntax prog) {

    HashMap<String,HMType> env = new HashMap<>();
    // Simple types
    HMVar bool  = new HMVar(TypeInt.BOOL);
    HMVar int64 = new HMVar(TypeInt.INT64);

    // Primitives
    HMVar var1 = new HMVar();
    HMVar var2 = new HMVar();
    env.put("pair",Oper.fun(var1, Oper.fun(var2, new Oper("pair",var1,var2))));

    HMVar var3 = new HMVar();
    env.put("if/else",Oper.fun(bool,Oper.fun(var3,Oper.fun(var3,var3))));

    env.put("dec",Oper.fun(int64,int64));
    env.put("*",Oper.fun(int64,Oper.fun(int64,int64)));
    env.put("==0",Oper.fun(int64,bool));

    return prog.hm(env, new HashSet<>());
  }
  static void reset() { HMVar.reset(); }


  public static abstract class Syntax {
    abstract HMType hm(HashMap<String,HMType> env, HashSet<HMVar> nongen);
  }
  public static class Con extends Syntax {
    final Type _t;
    Con(Type t) { _t=t; }
    @Override public String toString() { return _t.toString(); }
    @Override HMType hm(HashMap<String,HMType> env, HashSet<HMVar> nongen) {
      return new HMVar(_t);
    }
  }
  public static class Ident extends Syntax {
    final String _name;
    Ident(String name) { _name=name; }
    @Override public String toString() { return _name; }
    @Override HMType hm(HashMap<String,HMType> env, HashSet<HMVar> nongen) {
      HMType t = env.get(_name);
      if( t==null )
        throw new RuntimeException("Parse error, "+_name+" is undefined");
      HMType f = t.fresh(nongen);
      return f;
    }
  }
  public static class Lambda extends Syntax {
    final String _arg0;
    final Syntax _body;
    Lambda(String arg0, Syntax body) { _arg0=arg0; _body=body; }
    @Override public String toString() { return "{ "+_arg0+" -> "+_body+" }"; }
    @Override HMType hm(HashMap<String,HMType> env, HashSet<HMVar> nongen) {
      HMVar tnew = new HMVar();
      // Push _arg0->tnew into env & nongen, popping them off after doing body
      env.put(_arg0,tnew);
      nongen.add(tnew);
      HMType trez = _body.hm(env,nongen);
      nongen.remove(tnew);
      env.remove(_arg0);
      return Oper.fun(tnew,trez);
    }
  }
  public static class Let extends Syntax {
    final String _arg0;
    final Syntax _def, _body;
    Let(String arg0, Syntax def, Syntax body) { _arg0=arg0; _def=def; _body=body; }
    @Override public String toString() { return "let "+_arg0+" = "+_def+" in "+_body+" }"; }
    @Override HMType hm(HashMap<String,HMType> env, HashSet<HMVar> nongen) {
      HMVar tndef = new HMVar();
      // Push _arg0->tnew into env & nongen, popping them off after doing body
      env.put(_arg0,tndef);
      nongen.add(tndef);
      HMType tdef = _def.hm(env,nongen);
      nongen.remove(tndef);
      tndef.union(tdef);
      HMType trez = _body.hm(env,nongen);
      env.remove(_arg0);
      return trez;
    }
  }
  public static class Apply extends Syntax {
    final Syntax _fun, _arg;
    Apply(Syntax fun, Syntax arg) { _fun=fun; _arg=arg; }
    @Override public String toString() { return "("+_fun+" "+_arg+")"; }
    @Override HMType hm(HashMap<String,HMType> env, HashSet<HMVar> nongen) {
      HMType tfun = _fun.hm(env,nongen);
      HMType targ = _arg.hm(env,nongen);
      HMType trez = new HMVar();
      HMType nfun = Oper.fun(targ,trez);
      nfun.union(tfun);
      return trez;
    }
  }



  public static abstract class HMType {
    HMType _u;                  // U-F; always null for Oper
    abstract HMType union(HMType t);
    abstract HMType find();
    public String str() { return find()._str(); }
    abstract String _str();
    boolean is_top() { return _u==null; }

    HMType fresh(HashSet<HMVar> nongen) {
      HashMap<HMType,HMType> vars = new HashMap<>();
      return _fresh(nongen,vars);
    }
    HMType _fresh(HashSet<HMVar> nongen, HashMap<HMType,HMType> vars) {
      HMType t2 = find();
      if( t2 instanceof HMVar ) {
        return t2.occurs_in(nongen) //
          ? t2                      // Keep same var
          : vars.computeIfAbsent(t2, e -> new HMVar(((HMVar)t2)._t));
      } else {
        Oper op = (Oper)t2;
        HMType[] args = new HMType[op._args.length];
        for( int i=0; i<args.length; i++ )
          args[i] = op._args[i]._fresh(nongen,vars);
        return new Oper(op._name,args);
      }
    }

    boolean occurs_in(HashSet<HMVar>nongen) {
      for( HMVar x : nongen ) if( occurs_in_type(x) ) return true;
      return false;
    }
    boolean occurs_in(HMType[] args) {
      for( HMType x : args ) if( occurs_in_type(x) ) return true;
      return false;
    }
    boolean occurs_in_type(HMType v) {
      assert is_top();
      HMType y = v.find();
      if( y==this )
        return true;
      if( y instanceof Oper )
        return occurs_in(((Oper)y)._args);
      return false;
    }
  }

  static class HMVar extends HMType {
    private Type _t;
    private final int _uid;
    private static int CNT;
    HMVar() { this(Type.ANY); }
    HMVar(Type t) { _uid=CNT++; _t=t; }
    static void reset() { CNT=1; }
    public Type type() { assert is_top(); return _t; }
    @Override public String toString() {
      String s = _str();
      if( _u!=null ) s += ">>"+_u;
      return s;
    }
    @Override public String _str() {
      String s = "v"+_uid;
      if( _t!=Type.ANY ) s += ":"+_t;
      return s;
    }

    @Override HMType find() {
      HMType u = _u;
      if( u==null ) return this; // Top of union tree
      if( u._u==null ) return u; // One-step from top
      // Classic U-F rollup
      while( u._u!=null ) u = u._u; // Find the top
      HMType x = this;              // Collapse all to top
      while( x._u!=u ) { HMType tmp = x._u; x._u=u; x=tmp;}
      return u;
    }
    @Override HMType union(HMType that) {
      if( _u!=null ) return find().union(that);
      if( that instanceof HMVar ) that = that.find();
      if( this==that ) return this; // Do nothing
      if( occurs_in_type(that) )
        throw new RuntimeException("recursive unification");

      if( that instanceof HMVar ) {
        HMVar v2 = (HMVar)that;
        v2._t = _t.meet(v2._t);
      }
      else assert _t==Type.ANY; // Else this var is un-MEETd with any Con
      return _u = that;         // Classic U-F union
    }
  }

  static class Oper extends HMType {
    final String _name;
    final HMType[] _args;
    Oper(String name, HMType... args) { _name=name; _args=args; }
    static Oper fun(HMType... args) { return new Oper("->",args); }
    @Override public String toString() {
      if( _name.equals("->") ) return "{ "+_args[0]+" -> "+_args[1]+" }";
      return _name+" "+Arrays.toString(_args);
    }
    @Override public String _str() {
      if( _name.equals("->") ) 
            return "{ "+_args[0].str()+" -> "+_args[1].str()+" }";
      SB sb = new SB().p(_name).p('(');
      for( HMType t : _args )
        sb.p(t.str()).p(',');
      return sb.unchar().p(')').toString();
    }

    @Override HMType find() { return this; }
    @Override HMType union(HMType that) {
      if( !(that instanceof Oper) ) return that.union(this);
      Oper op2 = (Oper)that;
      if( !_name.equals(op2._name) ||
          _args.length != op2._args.length )
        throw new RuntimeException("Cannot unify "+this+" and "+that);
      for( int i=0; i<_args.length; i++ )
        _args[i].union(op2._args[i]);
      return this;
    }
  }
}
