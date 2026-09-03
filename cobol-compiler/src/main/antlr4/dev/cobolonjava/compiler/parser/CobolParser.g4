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
    PERIOD, COMMA, SEMICOLON, LPAREN, RPAREN,

    // 島 (不透明トークン)
    PICTURE_STRING, EXEC_BLOCK,

    // 語と定数
    IDENTIFIER, LITERAL, NUMBER,

    // 見出し部
    IDENTIFICATION, ID, DIVISION, PROGRAM_ID, PROGRAM,
    COMMON, INITIAL, RECURSIVE, IS, END,

    // 環境部・手続き部 (中身は次の増分)
    ENVIRONMENT, PROCEDURE,

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
      (DEPENDING ON? dataName)?
      occursKeyClause*
      occursIndexedClause?
    ;

occursKeyClause
    : (ASCENDING | DESCENDING) KEY? IS? dataName+
    ;

occursIndexedClause
    : INDEXED BY? IDENTIFIER+
    ;

valueClause
    : (VALUE | VALUES) (IS | ARE)? literal
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

// ---- 手続き部 (中身は次の増分) ----

procedureDivision
    : PROCEDURE DIVISION PERIOD
    ;
