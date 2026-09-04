// COBOL の構文解析器 (方針 ARC-8、決定 D-5)。
//
// これは字句解析器を持たない「構文文法」である。トークンはプリプロセッサ
// (dev.cobolonjava.compiler.source.Tokenizer) が作り、SourceTokenSource が
// ここへ渡す。カラム、継続行、COPY、コンパイラ指示文、そして「島」
// (PICTURE 句の文字列と EXEC ブロック) は、この文法に到達する前に消えている。
//
// 予約語は tokens で宣言した名前だけである。SourceTokenSource は COBOL 語を
// 「ハイフンを下線に置き換えた名前」で引く。COMP-3 は COMP_3 になる。
// 宣言していない語は利用者定義語 (IDENTIFIER) として扱う。
parser grammar CobolParser;

tokens {
    // 区切り文字
    PERIOD, COMMA, SEMICOLON, LPAREN, RPAREN, COLON,

    // 関係演算子の記号形。COBOL 語として書けない綴りなので、
    // SourceTokenSource が綴りから直接この種別へ写す
    EQUAL_SIGN, GREATER_SIGN, LESS_SIGN, GREATER_EQUAL_SIGN, LESS_EQUAL_SIGN, NOT_EQUAL_SIGN,
    PLUS_SIGN, MINUS_SIGN, TIMES_SIGN, DIVIDE_SIGN, POWER_SIGN,

    // 島 (不透明トークン)
    PICTURE_STRING, EXEC_BLOCK,

    // 語と定数
    IDENTIFIER, LITERAL, NUMBER,

    // 見出し部
    IDENTIFICATION, ID, DIVISION, PROGRAM_ID, PROGRAM,
    COMMON, INITIAL, RECURSIVE, IS, END,

    // 環境部 (中身は次の増分)
    ENVIRONMENT,

    // 手続き部
    PROCEDURE, MOVE, CORRESPONDING, CORR, OF, IN,
    ADD, SUBTRACT, MULTIPLY, DIVIDE, FROM, GIVING, ROUNDED,
    SIZE, ERROR, END_ADD, END_SUBTRACT, END_MULTIPLY, END_DIVIDE, REMAINDER,
    COMPUTE, END_COMPUTE,
    IF, THEN, ELSE, END_IF, NEXT, SENTENCE, CONTINUE, GO, EXIT,
    PERFORM, END_PERFORM, UNTIL, VARYING, WITH, TEST, BEFORE, AFTER,
    UPON, NO, ADVANCING, USING, REFERENCE, CONTENT,
    CALL, END_CALL, CANCEL, EXCEPTION,
    INITIALIZE, SET, ALPHABETIC, ALPHANUMERIC, ALPHANUMERIC_EDITED, NUMERIC,
    NUMERIC_EDITED,
    ACCEPT, DATE, DAY, DAY_OF_WEEK, TIME, YYYYMMDD, YYYYDDD,
    UP, DOWN, SEARCH, END_SEARCH, AT,
    EVALUATE, END_EVALUATE, ALSO, ANY, OTHER, TRUE, FALSE,
    STOP, RUN, GOBACK,
    INSPECT, TALLYING, CONVERTING, FIRST, FOR, INITIAL,
    STRING, UNSTRING, DELIMITED, DELIMITER, COUNT, OVERFLOW, INTO,
    END_STRING, END_UNSTRING,
    AND, OR, NOT, GREATER, LESS, EQUAL, THAN, POSITIVE, NEGATIVE,

    // データ部
    DATA, SECTION, WORKING_STORAGE, LOCAL_STORAGE, LINKAGE, FILE,

    // データ記述項
    FILLER, REDEFINES, RENAMES, PICTURE, PIC, USAGE,
    OCCURS, TIMES, TO, DEPENDING, ON, ASCENDING, DESCENDING, KEY, INDEXED, BY,
    VALUE, VALUES, ARE, THRU, THROUGH,
    SIGN, LEADING, TRAILING, SEPARATE, CHARACTER,
    JUSTIFIED, JUST, RIGHT, LEFT,
    BLANK, WHEN, SYNCHRONIZED, SYNC,
    GLOBAL, EXTERNAL, GROUP_USAGE,

    // 用途
    DISPLAY, DISPLAY_1, BINARY, NATIONAL, INDEX, POINTER, PACKED_DECIMAL,
    COMP, COMP_1, COMP_2, COMP_3, COMP_4, COMP_5,
    COMPUTATIONAL, COMPUTATIONAL_1, COMPUTATIONAL_2, COMPUTATIONAL_3,
    COMPUTATIONAL_4, COMPUTATIONAL_5,

    // figurative constant
    ZERO, ZEROS, ZEROES, SPACE, SPACES, HIGH_VALUE, HIGH_VALUES,
    LOW_VALUE, LOW_VALUES, QUOTE, QUOTES, NULL, NULLS, ALL
}

// ---- 翻訳単位 ----

compilationUnit
    : programUnit+ EOF
    ;

programUnit
    : identificationDivision
      environmentDivision?
      dataDivision?
      procedureDivision?
      endProgramStatement?
    ;

// ---- 見出し部 ----

identificationDivision
    : (IDENTIFICATION | ID) DIVISION PERIOD programIdParagraph
    ;

programIdParagraph
    : PROGRAM_ID PERIOD programName (IS? programAttribute PROGRAM?)? PERIOD
    ;

programAttribute
    : COMMON
    | INITIAL
    | RECURSIVE
    ;

programName
    : IDENTIFIER
    | LITERAL
    ;

endProgramStatement
    : END PROGRAM programName PERIOD
    ;

// ---- 環境部 (中身は次の増分) ----

environmentDivision
    : ENVIRONMENT DIVISION PERIOD
    ;

// ---- データ部 ----

dataDivision
    : DATA DIVISION PERIOD dataDivisionSection*
    ;

dataDivisionSection
    : workingStorageSection
    | localStorageSection
    | linkageSection
    ;

workingStorageSection
    : WORKING_STORAGE SECTION PERIOD dataDescriptionEntry*
    ;

localStorageSection
    : LOCAL_STORAGE SECTION PERIOD dataDescriptionEntry*
    ;

linkageSection
    : LINKAGE SECTION PERIOD dataDescriptionEntry*
    ;

dataDescriptionEntry
    : levelNumber dataName? dataClause* PERIOD
    ;

levelNumber
    : NUMBER
    ;

dataName
    : IDENTIFIER
    | FILLER
    ;

dataClause
    : redefinesClause
    | renamesClause
    | pictureClause
    | usageClause
    | signClause
    | occursClause
    | valueClause
    | justifiedClause
    | blankWhenZeroClause
    | synchronizedClause
    | globalClause
    | externalClause
    ;

redefinesClause
    : REDEFINES dataName
    ;

renamesClause
    : RENAMES dataName ((THRU | THROUGH) dataName)?
    ;

pictureClause
    : (PICTURE | PIC) IS? PICTURE_STRING
    ;

usageClause
    : (USAGE IS?)? usageName
    ;

usageName
    : DISPLAY
    | DISPLAY_1
    | BINARY
    | NATIONAL
    | INDEX
    | POINTER
    | PACKED_DECIMAL
    | COMP | COMPUTATIONAL
    | COMP_1 | COMPUTATIONAL_1
    | COMP_2 | COMPUTATIONAL_2
    | COMP_3 | COMPUTATIONAL_3
    | COMP_4 | COMPUTATIONAL_4
    | COMP_5 | COMPUTATIONAL_5
    ;

signClause
    : (SIGN IS?)? (LEADING | TRAILING) (SEPARATE CHARACTER?)?
    ;

occursClause
    : OCCURS NUMBER (TO NUMBER)? TIMES?
      (DEPENDING ON? qualifiedDataName)?
      occursKeyClause*
      occursIndexedClause?
    ;

occursKeyClause
    : (ASCENDING | DESCENDING) KEY? IS? qualifiedDataName+
    ;

occursIndexedClause
    : INDEXED BY? IDENTIFIER+
    ;

// 88 レベルの条件名は値を並べたり範囲で書いたりできる。
// 通常のデータ項目の VALUE 句はその 1 個の場合にあたる
valueClause
    : (VALUE | VALUES) (IS | ARE)? valueRange (COMMA? valueRange)*
    ;

valueRange
    : literal ((THRU | THROUGH) literal)?
    ;

justifiedClause
    : (JUSTIFIED | JUST) RIGHT?
    ;

blankWhenZeroClause
    : BLANK WHEN? (ZERO | ZEROS | ZEROES)
    ;

synchronizedClause
    : (SYNCHRONIZED | SYNC) (LEFT | RIGHT)?
    ;

globalClause
    : IS? GLOBAL
    ;

externalClause
    : IS? EXTERNAL
    ;

literal
    : LITERAL
    | NUMBER
    | figurativeConstant
    ;

figurativeConstant
    : ALL? (ZERO | ZEROS | ZEROES
          | SPACE | SPACES
          | HIGH_VALUE | HIGH_VALUES
          | LOW_VALUE | LOW_VALUES
          | QUOTE | QUOTES
          | NULL | NULLS)
    | ALL LITERAL
    ;

// ---- 一意名 ----

// 同じ名前を複数の場所に置けるため、OF / IN で所属を絞る。
// 添字と部分参照はどちらも括弧で書くので、中身のコロンで見分ける
identifier
    : qualifiedDataName subscripts? referenceModifier?
    ;

qualifiedDataName
    : dataName ((OF | IN) dataName)*
    ;

subscripts
    : LPAREN subscript (COMMA? subscript)* RPAREN
    ;

referenceModifier
    : LPAREN subscript COLON subscript? RPAREN
    ;

subscript
    : NUMBER
    | qualifiedDataName
    ;

// ---- 手続き部 ----

// USING に並べた項目が、呼ぶ側から渡される領域に対応する
procedureDivision
    : PROCEDURE DIVISION (USING procedureParameter+)? PERIOD procedureBody
    ;

procedureParameter
    : (BY? (REFERENCE | VALUE))? identifier
    ;

// 段落名を持たない文が先に来ることがある
procedureBody
    : sentence* paragraph*
    ;

paragraph
    : paragraphName PERIOD sentence*
    ;

paragraphName
    : IDENTIFIER
    ;

sentence
    : statement+ PERIOD
    ;

statement
    : moveStatement
    | ifStatement
    | evaluateStatement
    | stopStatement
    | inspectStatement
    | stringStatement
    | unstringStatement
    | displayStatement
    | performStatement
    | continueStatement
    | goToStatement
    | exitStatement
    | callStatement
    | cancelStatement
    | initializeStatement
    | setStatement
    | acceptStatement
    | searchStatement
    | addStatement
    | subtractStatement
    | multiplyStatement
    | divideStatement
    | computeStatement
    ;

moveStatement
    : MOVE (CORRESPONDING | CORR)? moveSource TO identifier (COMMA? identifier)*
    ;

moveSource
    : identifier
    | literal
    ;

// ---- 条件 ----

condition
    : orCondition
    ;

orCondition
    : andCondition (OR andCondition)*
    ;

andCondition
    : notCondition (AND notCondition)*
    ;

notCondition
    : NOT? simpleCondition
    ;

// 条件名は「名前だけ」で書かれる。関係条件と符号条件を先に試す
simpleCondition
    : LPAREN condition RPAREN
    | relationCondition
    | signCondition
    | conditionNameCondition
    ;

relationCondition
    : arithmeticOperand relationalOperator arithmeticOperand
    ;

signCondition
    : arithmeticOperand IS? NOT? (POSITIVE | NEGATIVE | ZERO)
    ;

conditionNameCondition
    : identifier
    ;

relationalOperator
    : IS? NOT? relationalOperatorBody
    ;

relationalOperatorBody
    : GREATER THAN? OR EQUAL TO?
    | LESS THAN? OR EQUAL TO?
    | GREATER THAN?
    | LESS THAN?
    | EQUAL TO?
    | GREATER_EQUAL_SIGN
    | LESS_EQUAL_SIGN
    | NOT_EQUAL_SIGN
    | GREATER_SIGN
    | LESS_SIGN
    | EQUAL_SIGN
    ;

// ---- 制御構造 ----

// END-IF がなければ、本体は終止符または ELSE まで続く
ifStatement
    : IF condition THEN? ifBranch (ELSE ifBranch)? END_IF?
    ;

ifBranch
    : NEXT SENTENCE
    | statement+
    ;

continueStatement
    : CONTINUE
    ;

stopStatement
    : STOP RUN
    | GOBACK
    ;

// STRING は送出項目をつなげて 1 つの受取項目へ書く。
// 受取項目の残りは埋めない。書いた分だけが変わる
stringStatement
    : STRING stringSource+ INTO identifier (WITH? POINTER identifier)?
      overflowPhrases END_STRING?
    ;

stringSource
    : arithmeticOperand+ DELIMITED BY? (SIZE | arithmeticOperand)
    ;

// UNSTRING は送出項目を区切って複数の受取項目へ配る
unstringStatement
    : UNSTRING identifier (DELIMITED BY? unstringDelimiter (OR unstringDelimiter)*)?
      INTO unstringTarget+
      (WITH? POINTER identifier)?
      (TALLYING IN? identifier)?
      overflowPhrases END_UNSTRING?
    ;

unstringDelimiter
    : ALL? arithmeticOperand
    ;

unstringTarget
    : identifier (DELIMITER IN? identifier)? (COUNT IN? identifier)?
    ;

// ON OVERFLOW / NOT ON OVERFLOW は片方だけでも両方でも書ける
overflowPhrases
    : onOverflowPhrase? notOnOverflowPhrase?
    ;

onOverflowPhrase
    : ON? OVERFLOW statement+
    ;

notOnOverflowPhrase
    : NOT ON? OVERFLOW statement+
    ;

// INSPECT は 1 度の走査で、書かれた順に句を試す。
// TALLYING と REPLACING は同じ文に並べられる
inspectStatement
    : INSPECT identifier (tallyingPhrase replacingPhrase? | replacingPhrase | convertingPhrase)
    ;

tallyingPhrase
    : TALLYING tallyingCounter+
    ;

tallyingCounter
    : identifier FOR tallyingSpec+
    ;

tallyingSpec
    : CHARACTERS inspectRegion*
    | (ALL | LEADING) inspectOperand inspectRegion*
    ;

replacingPhrase
    : REPLACING replacingSpec+
    ;

replacingSpec
    : CHARACTERS BY inspectOperand inspectRegion*
    | (ALL | LEADING | FIRST) inspectOperand BY inspectOperand inspectRegion*
    ;

convertingPhrase
    : CONVERTING inspectOperand TO inspectOperand inspectRegion*
    ;

inspectRegion
    : (BEFORE | AFTER) INITIAL? inspectOperand
    ;

inspectOperand
    : identifier
    | literal
    ;

// EVALUATE は「主語と目的語を突き合わせる」書き方である。
// 突き合わせ方は主語が TRUE / FALSE かどうかで変わる
evaluateStatement
    : EVALUATE evaluateSubject (ALSO evaluateSubject)*
      evaluateBranch+
      (WHEN OTHER statement*)?
      END_EVALUATE?
    ;

// 同じ本体に複数の WHEN を並べられる
evaluateBranch
    : (WHEN evaluateObject (ALSO evaluateObject)*)+ statement*
    ;

evaluateSubject
    : TRUE
    | FALSE
    | arithmeticOperand
    ;

// 範囲は THRU で見分ける。残りは条件を先に試し、当たらなければ値とする
evaluateObject
    : ANY
    | NOT? arithmeticOperand (THRU | THROUGH) arithmeticOperand
    | TRUE
    | FALSE
    | condition
    | NOT? arithmeticOperand
    ;

// DISPLAY は USAGE の DISPLAY と綴りが同じである。文の先頭かどうかで見分ける
displayStatement
    : DISPLAY arithmeticOperand+ (UPON IDENTIFIER)? (WITH? NO ADVANCING)?
    ;

// SEARCH は表を順に見る。SEARCH ALL は 2 分探索であり、条件の形が限られる
searchStatement
    : SEARCH ALL identifier atEndPhrase? searchWhen+ END_SEARCH?
    | SEARCH identifier (VARYING identifier)? atEndPhrase? searchWhen+ END_SEARCH?
    ;

atEndPhrase
    : AT? END statement+
    ;

searchWhen
    : WHEN condition statement+
    ;

// ACCEPT は日付と時刻の特殊レジスタか、端末からの 1 行を受け取る
acceptStatement
    : ACCEPT identifier (FROM acceptSource)?
    ;

acceptSource
    : DATE YYYYMMDD?
    | DAY YYYYDDD?
    | DAY_OF_WEEK
    | TIME
    | identifier
    ;

// INITIALIZE は配下の基本項目それぞれへの転記の集まりである
initializeStatement
    : INITIALIZE identifier+ (WITH? FILLER)?
      (THEN? REPLACING initializeReplacing+)?
    ;

initializeReplacing
    : initializeCategory DATA? BY (identifier | literal)
    ;

initializeCategory
    : ALPHABETIC
    | ALPHANUMERIC_EDITED
    | ALPHANUMERIC
    | NUMERIC_EDITED
    | NUMERIC
    ;

// SET は条件名を成り立たせる形と、指標名を動かす形の 2 つがある
setStatement
    : SET identifier+ TO TRUE
    | SET identifier+ TO arithmeticOperand
    | SET identifier+ (UP | DOWN) BY arithmeticOperand
    ;

// 呼び先は文字定数か、実行時に名前が決まるデータ項目である
callStatement
    : CALL callTarget (USING callArgument+)? callExceptionPhrases END_CALL?
    ;

callTarget
    : literal
    | identifier
    ;

// BY REFERENCE / BY CONTENT は、次の指定が現れるまで後ろの引数すべてに効く
callArgument
    : BY? (REFERENCE | CONTENT | VALUE)
    | identifier
    | literal
    ;

callExceptionPhrases
    : onExceptionPhrase? notOnExceptionPhrase?
    ;

onExceptionPhrase
    : ON? (EXCEPTION | OVERFLOW) statement+
    ;

notOnExceptionPhrase
    : NOT ON? EXCEPTION statement+
    ;

// CANCEL は次に呼ばれたときの作業場所を初期状態へ戻す
cancelStatement
    : CANCEL callTarget+
    ;

// GO TO は段落の途中から別の段落へ飛ぶ。PERFORM と違い、戻ってこない
goToStatement
    : GO TO? paragraphName
    ;

// EXIT は何もしない。PERFORM ... THRU の範囲の終わりに置く段落のためにある
exitStatement
    : EXIT
    ;

// 段落を呼ぶ形と、その場に本体を書く形の 2 つがある。
// PERFORM のあとが段落名か、繰り返しの指定かで分かれる
performStatement
    : PERFORM procedureReference performPhrase?
    | PERFORM performPhrase? statement* END_PERFORM
    ;

procedureReference
    : paragraphName ((THRU | THROUGH) paragraphName)?
    ;

performPhrase
    : arithmeticOperand TIMES
    | performTest? UNTIL condition
    | performTest? varyingPhrase varyingAfterPhrase*
    ;

performTest
    : WITH? TEST (BEFORE | AFTER)
    ;

// VARYING の入れ子。AFTER のたびに内側の繰り返しが 1 段深くなる
varyingPhrase
    : VARYING varyingSpec
    ;

varyingAfterPhrase
    : AFTER varyingSpec
    ;

varyingSpec
    : identifier FROM arithmeticOperand BY arithmeticOperand UNTIL condition
    ;

// ---- 算術文 ----
//
// GIVING の有無で被演算子の役割が変わる。TO / FROM のあとに並ぶものは、
// GIVING があれば被演算子、なければ受取項目である。文法では区別せず、
// 意味解析で振り分ける

// CORRESPONDING の形は受取項目が 1 つだけである。名前の合う組ごとに 1 回ずつ計算する
addStatement
    : ADD (CORRESPONDING | CORR) identifier TO roundedTarget
      sizeErrorPhrases END_ADD?
    | ADD arithmeticOperand+ (TO roundedOperand+)? (GIVING roundedTarget+)?
      sizeErrorPhrases END_ADD?
    ;

subtractStatement
    : SUBTRACT (CORRESPONDING | CORR) identifier FROM roundedTarget
      sizeErrorPhrases END_SUBTRACT?
    | SUBTRACT arithmeticOperand+ FROM roundedOperand+ (GIVING roundedTarget+)?
      sizeErrorPhrases END_SUBTRACT?
    ;

multiplyStatement
    : MULTIPLY arithmeticOperand BY roundedOperand+ (GIVING roundedTarget+)?
      sizeErrorPhrases END_MULTIPLY?
    ;

// REMAINDER の形は商と剰余を 1 つずつ取る。GIVING は省略できない
divideStatement
    : DIVIDE arithmeticOperand (INTO | BY) arithmeticOperand
      GIVING roundedTarget REMAINDER roundedTarget
      sizeErrorPhrases END_DIVIDE?
    | DIVIDE arithmeticOperand (INTO | BY) roundedOperand+ (GIVING roundedTarget+)?
      sizeErrorPhrases END_DIVIDE?
    ;

// COMPUTE だけが式を取る。ほかの算術文は被演算子の並びである
computeStatement
    : COMPUTE roundedTarget+ EQUAL_SIGN expression sizeErrorPhrases END_COMPUTE?
    ;

// ---- 算術式 ----
//
// 優先順位は高いほうから 単項符号、べき乗、乗除、加減 である。ANTLR の左再帰では
// 先に書いた選択肢ほど優先順位が高い。
// COBOL は演算子の前後に空白を要求するため、`A-B` は 1 つの利用者定義語になる。
// 字句の切り出しはプリプロセッサが済ませており、ここでは並びを見るだけである

expression
    : (PLUS_SIGN | MINUS_SIGN) expression              # unaryExpression
    | <assoc=right> expression POWER_SIGN expression   # powerExpression
    | expression (TIMES_SIGN | DIVIDE_SIGN) expression # multiplicativeExpression
    | expression (PLUS_SIGN | MINUS_SIGN) expression   # additiveExpression
    | LPAREN expression RPAREN                         # parenthesizedExpression
    | arithmeticOperand                                # operandExpression
    ;

// ON SIZE ERROR / NOT ON SIZE ERROR は片方だけでも両方でも書ける
sizeErrorPhrases
    : onSizeErrorPhrase? notOnSizeErrorPhrase?
    ;

onSizeErrorPhrase
    : ON? SIZE ERROR statement+
    ;

notOnSizeErrorPhrase
    : NOT ON? SIZE ERROR statement+
    ;

arithmeticOperand
    : identifier
    | literal
    ;

// GIVING がなければ受取項目になるため、ROUNDED を書ける
roundedOperand
    : arithmeticOperand ROUNDED?
    ;

roundedTarget
    : identifier ROUNDED?
    ;
