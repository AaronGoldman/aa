package com.cliffc.aa;

import static com.cliffc.aa.AA.unimpl;

/** Operator parsing.

No leading expr, but required trailing expr e1:

  - Normal op parse
  - Examples:  ~e1     ;  +3     ;  -2     ; !@@##e1
    Called as:  e1.~_();   3.+_();   2.-_();      e1.!@@##_()

  - First char is "[" or 2nd or later is "{"; trailing op chars allowed.
    Requires a closing "}" or "]" op token, without an '='.
  - Examples:   [3]    ;  [% e1 %]
    Called as:  3.[_]();     e1.[%_%]()

Leading expr, required no trailing expr
  - Any normal op sequence, and is treated as a postfix call
  - Examples:   3++     ;  e0$$
  - Called as:  3._++() ;  e0._$$()

Leading expr, and trailing expr
  - Any normal op sequence, and is treated as an infix call.  Precedence from 1st char
  - Examples:   1+2*3          ;  e0+*e1
  - Called as:  1._+_(2._*_(3));  e0._+*_(e1)

Leading expr, and trailing expr
  - Op token with '[' (leading position ok) or '{' (2nd or later position)
  - Requires trailing op token with matching '}' or ']'.
  - No '=' in trailing op
  - Examples:   ary[idx]     ;  e0 %{ e1 }%  ;
  - Called as:  ary._[_](idx);  e0._%{_}%(e1);

Trinary, 3 exprs
  - Op token with '[' (leading position ok) or '{' (2nd or later position)
  - Requires trailing op token with matching '}' or ']'.
  - Yes '=' in trailing op
  - Examples:   ary[idx]=val      ;  dict["key"]=val      ;  e0 %{% e1 %}=% e2
    Called as:  ary._[_]=(idx,val);  dict._[_]=("key",val);  e0._%{%_%}=%_(e1,e2)

 */

// TODO: Mostly half-baked, and could have a lot of support from Env moved into here
public class Oper {
  public final String _name;    // Full name, with '_' where arguments go
  public final byte _prec;      // Precedence.  Not set for uniops and balanced ops.  Always > 0 for infix binary ops.
  public final int _nargs;

  public Oper(String name, int prec) {
    char c0 = name.charAt(0), c1 = name.charAt(0);
    assert c0!='{' &&  (c0!='_' || c1!='{'); // Too confusing
    // Count '_' for nargs
    int nargs=0;
    for( int i=0; (i = name.indexOf('_',i)+1)!=0; )
      nargs++;
    // Binary operators always have a precedence, other ops always have prec==0
    //assert (c0=='_' && name.charAt(name.length()-1)=='_' && nargs==2) == (prec>0);
    _name=name.intern();
    _prec=(byte)prec;
    _nargs=nargs;
  }

  // Build a binary oper, compute precedence based on 1st not-underscore
  // character.  Means, e.g. "<" and "<=" are forced to the same precedence.
  public Oper(String name) { this(name,prec(name.charAt(1))); }

  // Precedence is based on a single character
  public static final int MAX_PREC=4;
  private static int prec(char c) {
    return switch( c ) {
    case '*', '/', '%'      -> 3;
    case '+', '-'           -> 2;
    case '<', '>', '=', '!' -> 1; // includes <, <=, >, >=, ==, !=
    default -> throw unimpl();
    };
  }

  int len() { return _name.length(); }

  // True if op has balanced-op openers
  public boolean is_open() {
    return _name.indexOf('[')>=0 || _name.indexOf('{')>=2 || _name.indexOf('<')>=2;
  }

  // Parse a postfix op; update P or return null.
  // If the required trailing expr is not found, caller must unwind P.
  public static Oper post(Parse P) { throw unimpl(); }

  // Parse a binary op, OR a leading balanced op.
  public static Oper binbal(Parse P) { throw unimpl(); }

}
