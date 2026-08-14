/*
 * Compact Groovy-shaped grammar for serial/parallel parse benches.
 * Owns nls/sep, left-recursive expressions, closures, members, and
 * postfix calls without vendoring the production Groovy frontend.
 */
grammar GroovyLike;

compilationUnit
    :   nls (packageDecl sep?)? (typeDecl sep?)* EOF
    ;

packageDecl
    :   'package' qualifiedName
    ;

typeDecl
    :   'class' Identifier nls classBody
    ;

classBody
    :   '{' nls (member sep?)* '}'
    ;

member
    :   fieldDecl
    |   methodDecl
    ;

fieldDecl
    :   'def' Identifier ('=' expression)?
    ;

methodDecl
    :   'def' Identifier '(' params? ')' nls block
    ;

params
    :   Identifier (',' Identifier)*
    ;

block
    :   '{' nls (statement sep?)* '}'
    ;

statement
    :   block
    |   'return' expression
    |   'if' '(' expression ')' nls statement ('else' nls statement)?
    |   'def' Identifier ('=' expression)?
    |   expression
    ;

expression
    :   <assoc=right> expression '=' expression
    |   expression '?:' expression
    |   expression ('=='|'!='|'<'|'>'|'<='|'>=') expression
    |   expression ('+'|'-') expression
    |   expression ('*'|'/') expression
    |   postfix
    ;

postfix
    :   primary ('.' Identifier | '(' args? ')')*
    ;

args
    :   expression (',' expression)*
    ;

primary
    :   Identifier
    |   IntegerLiteral
    |   StringLiteral
    |   '(' expression ')'
    |   closure
    ;

closure
    :   '{' nls (statement sep?)* '}'
    ;

qualifiedName
    :   Identifier ('.' Identifier)*
    ;

nls : NL* ;
sep : (NL | SEMI)+ ;

IntegerLiteral : [0-9]+ ;
StringLiteral  : '"' (~["\\\r\n] | '\\' .)* '"' ;
Identifier     : [a-zA-Z_] [a-zA-Z_0-9]* ;
NL             : '\r'? '\n' ;
SEMI           : ';' ;
WS             : [ \t]+ -> skip ;
LINE_COMMENT   : '//' ~[\r\n]* -> skip ;
