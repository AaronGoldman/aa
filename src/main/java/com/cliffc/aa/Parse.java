package com.cliffc.aa;

import com.cliffc.aa.node.*;
import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.Bits;
import com.cliffc.aa.util.SB;

import java.text.NumberFormat;
import java.text.ParsePosition;
import java.util.BitSet;

/** an implementation of language AA
 *
 *  GRAMMAR:
 *  prog = stmts END
 *  stmts= stmt [; stmt]*[;]?   // multiple statments; final ';' is optional
 *  stmt = [id[:type]? =]* ifex // ids must not exist, and are available in later statements
 *  ifex = expr ? expr : expr   // trinary logic
 *  expr = term [binop term]*   // gather all the binops and sort by prec
 *  term = nfact [              // Any number of optional nfact modifiers
 *       =   ([stmts,]*) OR     // A function call, args (full stmts) are comma-delimited
 *       =   .field      OR     // Field lookup; not quite a binary operator because 'field' is not a Value
 *       =   :type              // Type annotation
 *       ]*                     // Any number of optional nfact modifiers
 *  nfact= uniop* fact          // Zero or more uniop calls over a fact
 *  fact = id                   // variable lookup
 *  fact = num                  // number
 *  fact = "str"                // string
 *  fact = (stmts)              // General statements parsed recursively
 *  fact = {func}               // Anonymous function declaration
 *  fact = .{ [id[:type]?[=stmt]?,]* } // Anonymous struct declaration; optional type, optional initial value, optional final comma
 *  fact = {binop}              // Special syntactic form of binop; no spaces allowed; returns function constant
 *  fact = {uniop}              // Special syntactic form of uniop; no spaces allowed; returns function constant
 *  binop= +-*%&|/<>!=          // etc; primitive lookup; can determine infix binop at parse-time
 *  uniop=  -!~                 // etc; primitive lookup; can determine infix uniop at parse-time
 *  func = { [[id[:type]?]* ->]? stmts} // Anonymous function declaration
 *  str  = [.\%]*               // String contents; \t\n\r\% standard escapes
 *  str  = %[num]?[.num]?fact   // Percent escape embeds a 'fact' in a string; "name=%name\n"
 *  type = tcon | tfun | tstruct // Types are a tcon or a tfun or a tstruct
 *  tcon = int, int[1,8,16,32,64], flt, flt[32,64], real, str
 *  tfun = {[[type]* ->]? type }// Function types mirror func decls
 *  tstruct = .{ [id[:type],]*} // Struct types are field names with optional types
 */

public class Parse {
  private final String _src;            // Source for error messages; usually a file name
  private Env _e;                       // Lookup context; pushed and popped as scopes come and go
  private final byte[] _buf;            // Bytes being parsed
  private int _x;                       // Parser index
  private int _line;                    // Zero-based line number
  private GVNGCM _gvn;                  // Pessimistic types
  
  // Fields strictly for Java number parsing
  private final NumberFormat _nf;
  private final ParsePosition _pp;
  private final String _str;

  Parse( String src, Env env, String str ) {
    _src = src;
    _line= 0;
    _e   = env;
    _buf = str.getBytes();
    _x   = 0;

    // Set fields strictly for Java number parsing
    _nf = NumberFormat.getInstance();
    _nf.setGroupingUsed(false);
    _pp = new ParsePosition(0);
    _str = str;          // Keep a complete string copy for java number parsing
    _gvn = Env._gvn;     // Pessimistic during parsing
  }

  // Parse the string in the given lookup context, and return an executable program
  TypeEnv go( ) { return prog(); }

  /** Parse a top-level:
   *  prog = ifex END */
  private TypeEnv prog() {
    // Currently only supporting exprs
    Node res = stmts();
    if( res == null ) res = con(TypeErr.ALL);
    _e._scope.add_def(ctrl());  // Hook, so not deleted
    _e._scope.add_def(res);     // Hook, so not deleted
    // Delete names at the top scope before final optimization.
    _e._scope.promote_forward_del_locals(_gvn,null);
    _gvn.iter();    // Pessimistic optimizations; might improve error situation
    // Run GCP from the global top, so we also get all the initial constants
    // and all users of those constants.  
    Env par = _e._par;
    _e._scope.add_def(par._scope); // Hook start control into all the constants
    _gvn.gcp(par._scope);          // Global Constant Propagation
    _e._scope.pop();               // Remove start hook
    res = _e._scope.pop();         // New and improved result
    Node ctrl = _e._scope.pop();   // Exit control

    // Gather errors
    Ary<String> errs = null;
    Type tres = Env.lookup_valtype(res);    // Result type
    String emsg = tres.errMsg();            // Error embedded in some subtype
    if( emsg != null ) errs = add_err(errs,emsg);
    // Disallow forward-refs as top-level results
    if( res.is_forward_ref() )
      errs = add_err(errs,forward_ref_err(((EpilogNode)res).fun().name()));
    // Hunt for typing errors in the alive code
    assert par._par==null;      // Top-level only
    BitSet bs = new BitSet();
    bs.set(_e._scope._uid);     // Do not walk top-level scope
    if( errs == null ) errs = res.walkerr_def(errs,bs,_gvn);
    if( errs == null ) errs = _e._scope.walkerr_use(null,new BitSet(),_gvn);
    if( errs == null && skipWS() != -1 ) errs = add_err(null,errMsg("Syntax error; trailing junk"));
    kill(res);
    return new TypeEnv(tres,_e,errs);
  }

  /** Parse a list of statements; final semi-colon is optional.
   *  stmts= stmt [; stmt]*[;]? 
   */
  private Node stmts() {
    Node stmt = stmt(), last = null;
    while( stmt != null ) {
      if( !peek(';') ) return stmt;
      last = stmt;
      stmt = stmt();
      if( stmt!=null ) kill(last); // prior expression result no longer alive in parser
    }
    return last;
  }
    
  /** A statement is a list of variables to let-assign, and an ifex for the
   *  value.  The variables must not already exist, and are available in all
   *  later statements.
   *  stmt = [id[:type]? =]* ifex
   */
  private Node stmt() {
    Ary<String> toks = new Ary<>(new String[1],0);
    Ary<Type  > ts   = new Ary<>(new Type  [1],0);
    while( true ) {
      int oldx = _x;
      String tok = token();  // Scan for 'id = ...'
      if( tok == null ) break;
      Type t = null;
      if( peek(':') && (t=type())==null ) _x = oldx; // attempt type parse
      if( !peek('=') ) { _x = oldx; break; } // Unwind token parse, and not assignment
      toks.add(tok);
      ts  .add(t  );
    }
    Node ifex = ifex();
    if( ifex == null )
      return toks._len == 0 ? null
        : con(err_ctrl("Missing ifex after assignment of '"+toks.last()+"'"));
    // Honor all type requests, all at once
    for( Type t : ts ) if( t != null ) ifex = gvn(new TypeNode(t,ifex,errMsg()));
    for( String tok : toks ) {
      Node n = _e.lookup(tok);
      if( n==null ) {           // Token not already bound to a value
        _e.add(tok,ifex);       // Bind token to a value
      } else { // Handle forward referenced function definitions
        if( n.is_forward_ref() ) ((EpilogNode)n).merge_ref_def(_gvn,tok,(EpilogNode)ifex);
        else err_ctrl0("Cannot re-assign val '"+tok+"'");
      }
    }
    return ifex;
  }
  
  /** Parse an if-expression, with lazy eval on the branches.  Assignments to
   *  new variables are allowed in either arm (as-if each arm is in a mini
   *  scope), and variables assigned on all live arms are available afterwards.
   *  ifex = expr ? expr : expr
   */
  private Node ifex() {
    Node expr = expr();
    if( expr == null ) return null; // Expr is required, so missing expr implies not any ifex
    if( !peek('?') ) return expr;   // No if-expression
    try( TmpNode ctrls = new TmpNode() ) {
      int vidx = _e._scope._defs._len; // Set of live variables
      Node ifex = gvn(new IfNode(ctrl(),expr));
      ctrls.add_def(ifex);      // Keep alive, even if 1st Proj kills last use, so 2nd Proj can hook
      set_ctrl(gvn(new CProjNode(ifex,1))); // Control for true branch
      Node tex = expr();
      ctrls.add_def(tex==null ? con(err_ctrl("missing expr after '?'")) : tex);
      ctrls.add_def(ctrl()); // 2 - hook true-side control
      ScopeNode t_scope = _e._scope.split(vidx); // Split out the new vars on the true side
      require(':');
      set_ctrl(gvn(new CProjNode(ifex,0)));
      Node fex = expr();
      ctrls.add_def(fex==null ? con(err_ctrl("missing expr after ':'")) : fex);
      ctrls.add_def(ctrl()); // 4 - hook false-side control
      ScopeNode f_scope = _e._scope.split(vidx); // Split out the new vars on the false side
      set_ctrl(init(new RegionNode(null,ctrls.at(2),ctrls.at(4))));
      _e._scope.common(this,t_scope,f_scope); // Add a PhiNode for all commonly defined variables
      _e._scope.add_def(gvn(new PhiNode(ctrl(),ctrls.at(1),ctrls.at(3)))); // Add a PhiNode for the result
    }
    return _e._scope.pop();
  }
  
  /** Parse an expression, a list of terms and infix operators.  The whole list
   *  is broken up into a tree based on operator precedence.
   *  expr = term [binop term]*
   */
  private Node expr() {
    Node term = term();
    if( term == null ) return null; // Term is required, so missing term implies not any expr
    // Collect 1st fcn/arg pair
    Ary<Node> funs = new Ary<>(new Node[1],0);
    try( TmpNode args = new TmpNode() ) {
      funs.add(null);   args.add_def(term);

      // Now loop for binop/term pairs: parse Kleene star of [binop term]
      while( true ) {
        int oldx = _x;
        String bin = token();
        if( bin==null ) break;    // Valid parse, but no more Kleene star
        Node binfun = _e.lookup_filter(bin,_gvn,2); // BinOp, or null
        if( binfun==null ) { _x=oldx; break; } // Not a binop, no more Kleene star
        term = term();
        if( term == null ) term = con(err_ctrl("missing expr after binary op "+bin));
        funs.add(binfun);  args.add_def(term);
      }

      // Have a list of interspersed operators and terms.
      // Build a tree with precedence.
      int max=-1;                 // First find max precedence.
      for( Node n : funs ) if( n != null ) max = Math.max(max,n.op_prec());
      // Now starting at max working down, group list by pairs into a tree
      while( args._defs._len > 1 ) {
        for( int i=1; i<funs.len(); i++ ) { // For all ops of this precedence level, left-to-right
          Node fun = funs.at(i);
          assert fun.op_prec() <= max;
          if( fun.op_prec() < max ) continue; // Not yet
          Node call = do_call(new CallNode(errMsg(),ctrl(),fun,args.at(i-1),args.at(i)));
          args.set_def(i-1,call,_gvn);
          funs.remove(i);  args.remove(i);  i--;
        }
        max--;
      }
      return args.del(0);       // Return the remaining expression
    }
  }

  /** Parse any number of optional function calls, field lookups and types
   *  annotations.
   *  term = nfact [
   *           ([stmts,]*) |      // A function call, args (full stmts) are delimited
   *           .field      |      // Field lookup; not quite a binary operator because 'field' is not a Value
   *           :type              // Typed
   *               ]*             // Any number of the above, in any order
   */
  private Node term() {
    Node n = nfact();    // Start with an nfact
    if( n == null ) return null;
    while( true ) {        // Any number of calls, field loads, and type annotations
      int oldx = _x;
      switch( skipWS() ) {
      case -1: return n;   // Just the original nfact
      case '(':            // Function application; parse out the argument list
        _x++;              // Skip paren
        try( CallNode args = new CallNode(errMsg()) ) {
          args.add_def(ctrl());
          args.add_def(n);              // Function pointer
          Node arg=stmts();             // First argument
          if( arg != null ) {           // Check for a no-arg fcn call
            args.add_def(arg);          // Add first arg
            while( peek(',') ) {        // Gather comma-separated args
              if( (arg=stmts()) == null ) arg = con(err_ctrl("Missing argument in function call"));
              args.add_def(arg);
            }
          }
          require(')');
          Type t = _gvn.type(n);
          n = t.is_fun_ptr() // Require being able to tell n is a function ptr at parse-time
            ? do_call(args)  // No syntax errors; flag Call not auto-close, and go again
            // Syntax error; auto-close reclaim args; but go again
            : con(err_ctrl("A function is being called, but "+t+" is not a function type"));
        }
        break;

      case '.':                 // Field parse
        _x++;                   // Skip dot
        String fld = token();   // Field name
        n = fld == null         // Missing field?
          ? con(err_ctrl("Missing field name after '.'"))
          :  gvn(new LoadNode(n,fld,errMsg()));
        if( peek('=') ) {       // Right now, field re-assigns of any type
          Node stmt = stmt();
          if( stmt == null ) n = con(err_ctrl("Missing stmt after assigning field '."+fld+"'"));
          else { kill(stmt); n = con(err_ctrl("Cannot re-assign field '."+fld+"'")); }
        }
        break;

      case ':':                 // Type parse
        _x++;                   // Skip colon
        Type t = type();
        if( t==null ) { _x = oldx; return n; } // No error for missing type, because can be ?: instead
        n = gvn(new TypeNode(t,n,errMsg()));
        break;
      default: return n;
      }
    }
  }
  
  
  /** Parse any leading unary ops before a factor
   *  nfact = fact | uniop nfact
   */
  private Node nfact() {
    int oldx = _x;
    String uni = token();
    if( uni!=null ) { // Valid parse
      Node unifun = _e.lookup_filter(uni,_gvn,1);
      if( unifun != null && unifun.op_prec() > 0 )  {
        Node arg = nfact(); // Recursive call
        if( arg == null ) { err_ctrl0("Call to unary function '"+uni+"', but missing the one required argument"); return null; }
        return do_call(new CallNode(errMsg(),ctrl(),unifun,arg));
      } else {
        _x=oldx;                // Unwind token parse and try again for a factor
      }
    }
    return fact();
  }

  /** Parse a factor, a leaf grammar token
   *  fact = num       // number
   *  fact = "string"  // string
   *  fact = (stmts)   // General statements parsed recursively
   *  fact = {binop}   // Special syntactic form of binop; no spaces allowed; returns function constant
   *  fact = {uniop}   // Special syntactic form of uniop; no spaces allowed; returns function constant
   *  fact = {func}    // Anonymous function declaration
   *  fact = id        // variable lookup, NOT a binop or uniop but might be e.g. function-valued, including un-/binops as values
   */
  private Node fact() {
    if( skipWS() == -1 ) return null;
    byte c = _buf[_x];
    if( '0' <= c && c <= '9' ) return con(number());
    if( '"' == c ) return con(string());
    int oldx = _x;
    if( peek1(c,'(') ) {           // a nested statement
      Node s = stmts();
      if( s==null ) { _x = oldx; return null; } // A bare "()" pair is not a statement
      require(')');
      return s;
    }
    // Anonymous function or operator
    if( peek1(c,'{') ) {
      String tok = token0();
      Node op = tok == null ? null : _e.lookup(tok);
      if( peek('}') && op != null && op.op_prec() > 0 )
        return op;              // Return operator as a function constant
      _x = oldx+1;              // Back to the opening paren
      return func();            // Anonymous function
    }
    // Anonymous struct
    if( peek2(c,".{") ) return struct();
    
    // Check for a valid 'id'
    String tok = token0();
    if( tok == null ) return null;
    Node var = _e.lookup(tok);
    if( var == null ) // Assume any unknown ref is a forward-ref of a recursive function
      return _e.add(tok,gvn(EpilogNode.forward_ref(_gvn,_e._scope,tok)));
    // Disallow uniop and binop functions as factors.
    if( var.op_prec() > 0 ) { _x = oldx; return null; }
    return var;
  }

  /** Parse an anonymous function; the opening '{' already parsed.  After the
   *  '{' comes an optional list of arguments and a '->' token, and then any
   *  number of statements separated by ';'.
   *  func = { [[id]* ->]? stmts }
   */
  private Node func() {
    int oldx = _x;
    Ary<String> ids = new Ary<>(new String[1],0);
    Ary<Type  > ts  = new Ary<>(new Type  [1],0);
    Ary<Parse > bads= new Ary<>(new Parse [1],0);
    while( true ) {
      String tok = token();
      if( tok == null ) { ids.clear(); _x=oldx; break; } // not a "[id]* ->"
      if( tok.equals("->") ) break;
      Type t = TypeErr.ALL;    // Untyped, most generic type
      Parse bad = errMsg();    // Capture location in case of type error
      if( peek(':') )          // Has type annotation?
        if( (t=type())==null ) throw AA.unimpl(); // return an error here
      ids .add(tok);
      ts  .add(t  );
      bads.add(bad);
    }
    Node old_ctrl = ctrl();
    FunNode fun = init(new FunNode(ids._len,old_ctrl));
    try( Env e = new Env(_e) ) {// Nest an environment for the local vars
      _e = e;                   // Push nested environment
      set_ctrl(fun);            // New control is function head
      int cnt=0;                // Add parameters to local environment
      for( int i=0; i<ids._len; i++ )
        _e.add(ids.at(i),gvn(new TypeNode(ts.at(i),gvn(new ParmNode(cnt++,ids.at(i),fun,con(TypeErr.ALL))),bads.at(i))));
      Node rpc = gvn(new ParmNode(-1,"rpc",fun,_gvn.con(TypeRPC.ALL_CALL)));
      Node rez = stmts();       // Parse function body
      require('}');             //
      Node epi = gvn(new EpilogNode(ctrl(),rez,rpc,fun));
      _e = _e._par;             // Pop nested environment
      set_ctrl(old_ctrl);       // Back to the pre-function-def control
      return epi;               // Return function; close-out and DCE 'e'
    }
  }

  /** Parse anonymous struct; the opening ".{" already parsed.  Next comes
   *  statements, with each assigned value becoming a struct member.  A lexical
   *  scope is made (non top-level assignments are removed at the end).
   * .{ [id[:type]?[=stmt]?,]* }
   */
  private Node struct() {
    try( Env e = new Env(_e) ) {// Nest an environment for the local vars
      _e = e;                   // Push nested environment
      Ary<String> toks = new Ary<>(new String[1],0);
      Ary<Type  > ts   = new Ary<>(new Type  [1],0);
      while( true ) {
        String tok = token();    // Scan for 'id'
        if( tok == null ) break; // end-of-struct-def
        Type t = TypeErr.ALL;    // Untyped, most generic type
        if( peek(':') )          // Has type annotation?
          if( (t=type())==null ) throw AA.unimpl(); // return an error here
        Node stmt = con(TypeErr.ANY);
        if( peek('=') )
          if( (stmt=stmt())==null ) throw AA.unimpl(); // return an error here
        stmt = gvn(new TypeNode(t,stmt,errMsg()));
        if( e._scope.get(tok)!=null ) {
          kill(stmt);
          t = err_ctrl("Cannot define field '." + tok + "' twice");
          e._scope.update(tok,con(t),_gvn);
          ts.set(toks.find(fld -> fld.equals(tok)),t);
        } else {
          e._scope.add(tok,stmt); // Field now available 'bare' inside rest of scope
          toks.add(tok);          // Gather for final type
          ts  .add(_gvn.type(stmt));
        }
        if( !peek(',') ) break; // Final comma is optional
      }
      require('}');
      _e = _e._par;             // Pop nested environment
      TypeStruct tstr = TypeStruct.make(toks.asAry(), TypeTuple.make(ts.asAry()));
      Node[] flds = e._scope.get(toks);
      return gvn(new NewNode(tstr,flds));
    }
  }
  
  private String token() { skipWS();  return token0(); }
  // Lexical tokens.  Any alpha, followed by any alphanumerics is a alpha-
  // token; alpha-tokens end with WS or any operator character.  Any collection
  // of the classic operator characters are a token, except that they will break
  // un-ambiguously.
  private String token0() {
    if( _x >= _buf.length ) return null;
    byte c=_buf[_x];  int x = _x;
    if(   isAlpha0(c) ) while( _x < _buf.length && isAlpha1(_buf[_x]) ) _x++;
    else if( isOp0(c) ) while( _x < _buf.length && isOp1   (_buf[_x]) ) _x++;
    else return null; // Not a token; specifically excludes e.g. all bytes >= 128, or most bytes < 32
    if( (c==':' || c==',') && _x-x==1 ) // Disallow bare ':' as a token; ambiguous with ?: and type annotations; same for ','
      { _x=x; return null; } // Unwind, not a token
    return new String(_buf,x,_x-x);
  }

  // Parse a number; WS already skipped and sitting at a digit.  Relies on
  // Javas number parsing.
  private Type number() {
    _pp.setIndex(_x);
    Number n = _nf.parse(_str,_pp);
    _x = _pp.getIndex();
    if( n instanceof Long   ) return TypeInt.con(n.  longValue());
    if( n instanceof Double ) return TypeFlt.con(n.doubleValue());
    throw new RuntimeException(n.getClass().toString()); // Should not happen
  }

  /** Parse a String; _x is at '"'.
   *  str  = [.\%]*               // String contents; \t\n\r\% standard escapes
   *  str  = %[num]?[.num]?fact   // Percent escape embeds a 'fact' in a string; "name=%name\n"
   */
  private Type string() {
    int oldx = ++_x;
    byte c;
    while( (c=_buf[_x++]) != '"' ) {
      if( c=='%' ) throw AA.unimpl();
      if( c=='\\' ) throw AA.unimpl();
      if( _x == _buf.length ) return err_ctrl("Unterminated string");
    }
    return TypeStr.make(0,new String(_buf,oldx,_x-oldx-1));
  }

  /** Parse a type or return null
   *  type = tcon | tfun | tstruct   // Types are a tcon or a tfun or a tstruct
   *  tcon = int, int[1,8,16,32,64], flt, flt[32,64], real, str
   *  tfun = {[[type]* ->]? type }// Function types mirror func decls
   *  tstruct = .{ [id[:type],]*} // Struct types are field names with optional types
   */
  private Type type() {
    Type t = type0();
    return (t==null || t==TypeErr.ANY) ? null : t;
  }
  // Type or null or TypeErr.ANY for '->' token
  private Type type0() {
    byte c = skipWS();
    if( peek1(c,'{') ) { // Function type
      Ary<Type> ts = new Ary<>(new Type[1],0);  Type t;
      while( (t=type0()) != null && t != TypeErr.ANY  )
        ts.add(t);              // Collect arg types
      Type ret;
      if( t==TypeErr.ANY ) {    // Found ->, expect return type
        ret = type0();
        if( ret == null ) throw AA.unimpl(); // should return TypeErr missing type after ->
      } else {                  // Allow no-args and simple return type
        if( ts._len != 1 ) throw AA.unimpl(); // should return TypeErr missing -> in tfun
        ret = ts.pop();         // Get single return type
      }
      require('}');
      return TypeTuple.make_fun_ptr(TypeFun.make(TypeTuple.make(ts.asAry()),ret,Bits.FULL));
    }

    if( peek2(c,".{") ) { // Struct type
      Ary<String> flds = new Ary<>(new String[1],0);
      Ary<Type  > ts   = new Ary<>(new Type  [1],0);
      while( true ) {
        String tok = token();    // Scan for 'id'
        if( tok == null ) break; // end-of-struct-def
        Type t = TypeErr.ALL;    // Untyped, most generic type
        if( peek(':') )          // Has type annotation?
          if( (t=type())==null ) throw AA.unimpl(); // return an error here, missing type
        if( flds.find(tok) != -1 ) throw AA.unimpl(); // cannot use same field name twice
        flds.add(tok);          // Gather for final type
        ts  .add(t  );
        if( !peek(',') ) break; // Final comma is optional
      }
      require('}');
      return TypeStruct.make(flds.asAry(), TypeTuple.make(ts.asAry()));
    }

    // Primitive type
    int oldx = _x;
    String tok = token();
    if( tok==null ) return null;
    if( tok.equals("->") ) return TypeErr.ANY; // Found -> return sentinal
    Type t = _e.lookup_type(tok);
    if( t==null ) _x = oldx;  // Unwind if not a known type
    return t;
  }

  // Require a specific character (after skipping WS) or polite error
  private void require( char c ) {
    if( peek(c) ) return;
    err_ctrl0("Expected '"+c+"' but "+(_x>=_buf.length?"ran out of text":"found '"+(char)(_buf[_x])+"' instead"));
  }

  // Skip WS, return true&skip if match, false&noskip if miss.
  private boolean peek( char c ) { return peek1(skipWS(),c); }
  // Already skipped WS & have character;
  // return true&skip if match, false&noskip if miss.
  private boolean peek1( byte c0, char c ) {
    assert (c0==-1 || c0== _buf[_x]) && !isWS(c0);
    if( c0!=c ) return false;
    _x++;                       // Skip peeked character
    return true;
  }
  // Already skipped WS & have character;
  // return true&skip if match, false&noskip if miss.
  private boolean peek2( byte c0, String s2 ) {
    if( c0 != s2.charAt(0) ) return false;
    if( _x+1 >= _buf.length || _buf[_x+1] != s2.charAt(1) ) return false;
    _x+=2;                      // Skip peeked characters
    return true;
  }
  
  /** Advance parse pointer to the first non-whitespace character, and return
   *  that character, -1 otherwise.  */
  private byte skipWS() {
    while( _x < _buf.length ) {
      byte c = _buf[_x];
      if( c=='/' && _x+1 < _buf.length && _buf[_x+1]=='/' ) { skipEOL()  ; continue; }
      if( c=='/' && _x+1 < _buf.length && _buf[_x+1]=='*' ) { skipBlock(); continue; }
      if( c=='\n' ) _line++;
      if( !isWS(c) ) return c;
      _x++;
    }
    return -1;
  }
  private void skipEOL  () { throw AA.unimpl(); }
  private void skipBlock() { throw AA.unimpl(); }

  /** Return true if `c` passes a test */
  private static boolean isWS    (byte c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
  private static boolean isAlpha0(byte c) { return ('a'<=c && c <= 'z') || ('A'<=c && c <= 'Z') || (c=='_'); }
  private static boolean isAlpha1(byte c) { return isAlpha0(c) || ('0'<=c && c <= '9'); }
  private static boolean isOp0   (byte c) { return "!#$%*+,-.;:=<>?@^[]~/&".indexOf(c) != -1; }
  private static boolean isOp1   (byte c) { return isOp0(c); }

  public Node gvn (Node n) { return n==null ? null : _gvn.xform(n); }
  private <N extends Node> N init( N n ) { return n==null ? null : _gvn.init (n); }
  private void kill( Node n ) { if( n._uses._len==0 ) _gvn.kill(n); }
  public Node ctrl() { return _e._scope.get(" control "); }
  // Set and return a new control
  private void set_ctrl(Node n) { _e._scope.update(" control ",n,_gvn); }

  private Node con( Type t ) { return _gvn.con(t); }

  private Node do_call( Node call0 ) {
    Node call = gvn(call0);
    // Primitives frequently inline immediately, and do not need following
    // control/data projections.
    if( !(call instanceof CallNode)) return call;

    call.add_def(call);         // Hook, so not deleted after 1st use
    set_ctrl(gvn(new CProjNode(call,0)));
    Node ret = gvn(new ProjNode(call,1));
    ret.add_def(ret);           // Hook, so not deleted when call goes
    if( call.pop()._uses._len==0 )
      _gvn.kill(call);
    return ret.pop();
  }

  // Whack current control with a syntax error
  private TypeErr err_ctrl(String s) { return TypeErr.make(err_ctrl0(s)); }
  private String err_ctrl0(String s) {
    String msg = errMsg(s);
    set_ctrl(gvn(new ErrNode(ctrl(),msg)));
    return msg;
  }

  public static Ary<String> add_err( Ary<String> errs, String msg ) {
    if( errs == null ) errs = new Ary<>(new String[1],0);
    errs.add(msg);
    return errs;
  }
  // Make a private clone just for delayed error messages
  private Parse( Parse P ) {
    _src = P._src;  
    _buf = P._buf;
    _x   = P._x;
    _line= P._line;
    _e   = null;  _gvn = null;  _nf  = null;  _pp  = null;  _str = null;
  }
  // Delayed error message
  private Parse errMsg() { return new Parse(this); }

  // Polite error message for mismatched types
  public String typerr( Type t0, Type t1 ) {
    return t0.is_forward_ref() // Forward/unknown refs as args to a call report their own error
      ? forward_ref_err(FunNode.name(((TypeTuple)t0).get_fun().fidx()))
      : errMsg(t0+" is not a "+t1);
  }

  // Standard mis-use of a forward-ref error (assumed to be a forward-decl of a
  // recursive function; all other uses are treated as an unknown-ref error).
  private String forward_ref_err(String name) { return errMsg("Unknown ref '"+name+"'"); }
  // Build a string of the given message, the current line being parsed,
  // and line of the pointer to the current index.
  public String errMsg(String s) {
    if( s.charAt(0)=='\n' ) return s;
    // find line start
    int a=_x;
    while( a > 0 && _buf[a-1] != '\n' ) --a;
    if( _buf[a]=='\r' ) a++; // do not include leading \n or \n\r
    // find line end
    int b=_x;
    while( b < _buf.length && _buf[b] != '\n' ) b++;
    if( b < _buf.length ) b--; // do not include trailing \n or \n\r
    // error message
    SB sb = new SB().p('\n').p(_src).p(':').p(_line).p(':').p(s).p('\n');
    sb.p(new String(_buf,a,b-a)).p('\n');
    int line_start = 0;
    for( int i=line_start; i<_x; i++ )
      sb.p(' ');
    return sb.p("^\n").toString();
  }
  // Handy for the debugger to print 
  @Override public String toString() { return new String(_buf,_x,_buf.length-_x); }

}
