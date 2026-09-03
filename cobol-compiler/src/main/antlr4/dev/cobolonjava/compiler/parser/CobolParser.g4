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
    IF, THEN, ELSE, END_IF, NEXT, SENTENCE, CONTINUE,
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

procedureDivision
    : PROCEDURE DIVISION PERIOD procedureBody
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
    | continueStatement
    | addStatement
    | subtractStatement
    | multiplyStatement
    | divideStatement
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

// ---- 算術文 ----
//
// GIVING の有無で被演算子の役割が変わる。TO / FROM のあとに並ぶものは、
// GIVING があれば被演算子、なければ受取項目である。文法では区別せず、
// 意味解析で振り分ける

addStatement
    : ADD arithmeticOperand+ (TO roundedOperand+)? (GIVING roundedTarget+)?
    ;

subtractStatement
    : SUBTRACT arithmeticOperand+ FROM roundedOperand+ (GIVING roundedTarget+)?
    ;

multiplyStatement
    : MULTIPLY arithmeticOperand BY roundedOperand+ (GIVING roundedTarget+)?
    ;

divideStatement
    : DIVIDE arithmeticOperand (INTO | BY) roundedOperand+ (GIVING roundedTarget+)?
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
