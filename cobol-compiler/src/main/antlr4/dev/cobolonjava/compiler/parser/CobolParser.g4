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
    // 区切り文字。コンマとセミコロンは飾りなので SourceTokenSource が落とす。
    // 名前だけ残してあるのは、利用者定義語として引かれないようにするためである
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

    // 環境部
    ENVIRONMENT, CONFIGURATION, SOURCE_COMPUTER, OBJECT_COMPUTER, SPECIAL_NAMES,
    CURRENCY, DECIMAL_POINT,
    ALPHABET, STANDARD_1, STANDARD_2, NATIVE, EBCDIC,
    INPUT_OUTPUT, FILE_CONTROL, SELECT, OPTIONAL, ASSIGN, ORGANIZATION, LINE, SEQUENTIAL,
    ACCESS, MODE, STATUS, RECORDING, LABEL, STANDARD, OMITTED, BLOCK, CONTAINS, RECORDS,
    RELATIVE, RANDOM, DYNAMIC, ALTERNATE, DUPLICATES,
    RESERVE, AREA, AREAS, PASSWORD, PADDING,
    I_O_CONTROL, SAME, SORT_MERGE, MULTIPLE, TAPE, POSITION, RERUN, APPLY, EVERY,
    LINAGE, FOOTING, TOP, BOTTOM,

    // 手続き部
    PROCEDURE, MOVE, CORRESPONDING, CORR, OF, IN,
    ADD, SUBTRACT, MULTIPLY, DIVIDE, FROM, GIVING, ROUNDED,
    SIZE, ERROR, END_ADD, END_SUBTRACT, END_MULTIPLY, END_DIVIDE, REMAINDER,
    COMPUTE, END_COMPUTE,
    IF, THEN, ELSE, END_IF, NEXT, SENTENCE, CONTINUE, GO, EXIT,
    PERFORM, END_PERFORM, UNTIL, VARYING, WITH, TEST, BEFORE, AFTER,
    UPON, NO, ADVANCING, USING, REFERENCE, CONTENT, LINES, PAGE,
    CALL, END_CALL, CANCEL, EXCEPTION,
    INITIALIZE, SET, ALPHABETIC, ALPHANUMERIC, ALPHANUMERIC_EDITED, NUMERIC,
    NUMERIC_EDITED,
    ACCEPT, DATE, DAY, DAY_OF_WEEK, TIME, YYYYMMDD, YYYYDDD,
    UP, DOWN, SEARCH, END_SEARCH, AT,
    OPEN, CLOSE, READ, WRITE, INPUT, OUTPUT, I_O, EXTEND,
    END_READ, END_WRITE, REWRITE, END_REWRITE, INVALID, FD, RECORD,
    DELETE, END_DELETE, START, END_START, DECLARATIVES, USE,
    SD, SORT, MERGE, RELEASE, RETURN, END_RETURN, ORDER, COLLATING, SEQUENCE,
    EVALUATE, END_EVALUATE, ALSO, ANY, OTHER, TRUE, FALSE,
    STOP, RUN, GOBACK,
    INSPECT, TALLYING, CONVERTING, FIRST, FOR, INITIAL,
    STRING, UNSTRING, DELIMITED, DELIMITER, COUNT, OVERFLOW, INTO,
    END_STRING, END_UNSTRING,
    AND, OR, NOT, GREATER, LESS, EQUAL, THAN, POSITIVE, NEGATIVE,
    FUNCTION, ALTER, PROCEED, DEBUGGING, PROCEDURES, REFERENCES,

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
    : ENVIRONMENT DIVISION PERIOD configurationSection? inputOutputSection?
    ;

// ---- 入出力節 ----

inputOutputSection
    : INPUT_OUTPUT SECTION PERIOD fileControlParagraph? ioControlParagraph?
    ;

// 入出力の制御。領域の共有 (SAME)、1 巻のテープに何本置くか (MULTIPLE FILE)、
// 再開の点 (RERUN) の指定である。<b>どれも翻訳の結果には効かない</b> —
// 記憶と装置の割り付けの話であり、こちらでは実行時の資源管理が引き受ける。
// 読み飛ばすが、読めないとは言わない
// <b>1 つの終止符に指定が何本も入る。</b>CCVS85 は SAME を 2 行並べて最後だけ
// 終止符を打つ。指定ごとに終止符を要求すると、正しいプログラムを断ってしまう
ioControlParagraph
    : I_O_CONTROL PERIOD (ioControlEntry+ PERIOD)*
    ;

ioControlEntry
    : SAME (RECORD | SORT | SORT_MERGE)? AREA? FOR? IDENTIFIER+
    | MULTIPLE FILE TAPE? CONTAINS? multipleFile+
    | RERUN ~(PERIOD | SAME | MULTIPLE | RERUN | APPLY)*
    | APPLY ~(PERIOD | SAME | MULTIPLE | RERUN | APPLY)*
    ;

multipleFile
    : IDENTIFIER (POSITION NUMBER)?
    ;

fileControlParagraph
    : FILE_CONTROL PERIOD selectEntry*
    ;

// ASSIGN に書くのは DD 名であり、ファイルの場所そのものではない。
//
// <b>句の順は決まっていない。</b>ASSIGN も句の 1 つであり、ACCESS や ORGANIZATION の
// あとに書かれることがある。規格が並びを決めているのは SELECT と名前だけである。
// 必ず 1 つ要るという検査は意味解析でやる — 文法で位置まで縛ると、
// 順を入れ替えただけの正しいプログラムを「読めない」と断ってしまう
selectEntry
    : SELECT OPTIONAL? IDENTIFIER selectClause* PERIOD
    ;

selectClause
    : assignClause
    | ORGANIZATION IS? RELATIVE
    | ORGANIZATION IS? organizationName
    | organizationName
    | ACCESS MODE? IS? (SEQUENTIAL | RANDOM | DYNAMIC)
    // FILE は省いてよい。CCVS85 は「STATUS IS X」とだけ書く
    | FILE? STATUS IS? identifier
    | RECORDING MODE? IS? IDENTIFIER
    // 裸の RELATIVE は編成の指定、名前が続けば相対キーの指定である
    | RELATIVE (KEY? IS? identifier)?
    | ALTERNATE RECORD? KEY? IS? identifier (WITH? DUPLICATES)?
    | RECORD KEY? IS? identifier
    // 入出力の領域をいくつ取るか。実行時の緩衝の話であり、翻訳の結果には効かない
    | RESERVE (NUMBER | NO) ALTERNATE? (AREA | AREAS)?
    | PASSWORD IS? identifier
    // 順編成のブロックの埋め草と、レコードの切れ目。どちらも装置の話である
    | PADDING CHARACTER? IS? (identifier | literal)
    | RECORD DELIMITER IS? (IDENTIFIER | STANDARD_1)
    ;

assignClause
    : ASSIGN TO? (IDENTIFIER | LITERAL)+
    ;

// ORGANIZATION IS は省いてよい
organizationName
    : LINE? SEQUENTIAL
    | INDEXED
    ;

configurationSection
    : CONFIGURATION SECTION PERIOD configurationParagraph*
    ;

configurationParagraph
    : sourceComputerParagraph
    | objectComputerParagraph
    | specialNamesParagraph
    ;

// 翻訳する機械と動かす機械の指定は、翻訳の結果に効かない。段落ごと読み飛ばす
sourceComputerParagraph
    : SOURCE_COMPUTER PERIOD ~PERIOD* PERIOD
    ;

// 動かす機械の指定そのものは翻訳の結果に効かないので読み飛ばす。
// PROGRAM COLLATING SEQUENCE だけは効く (要件 FR-054)
objectComputerParagraph
    : OBJECT_COMPUTER PERIOD objectComputerPart* PERIOD
    ;

objectComputerPart
    : programCollatingSequence
    | ~PERIOD
    ;

programCollatingSequence
    : PROGRAM COLLATING? SEQUENCE IS? IDENTIFIER
    ;

// SPECIAL-NAMES は段落の最後にピリオドが 1 つ来る。句の区切りは要らない
specialNamesParagraph
    : SPECIAL_NAMES PERIOD specialNamesEntry* PERIOD?
    ;

specialNamesEntry
    : CURRENCY SIGN? IS? literal
    | DECIMAL_POINT IS? IDENTIFIER
    | alphabetClause
    | IDENTIFIER IS IDENTIFIER
    ;

// ALPHABET は照合順序に名前を付ける (要件 FR-054)
alphabetClause
    : ALPHABET IDENTIFIER IS? alphabetSpecification
    ;

alphabetSpecification
    : STANDARD_1
    | STANDARD_2
    | NATIVE
    | EBCDIC
    | alphabetPosition+
    ;

// 1 つの位置に置く文字。ALSO で並べたものは同じ位置になる
alphabetPosition
    : literal ((THROUGH | THRU) literal | (ALSO literal)+)?
    ;

// ---- データ部 ----

dataDivision
    : DATA DIVISION PERIOD dataDivisionSection*
    ;

dataDivisionSection
    : fileSection
    | workingStorageSection
    | localStorageSection
    | linkageSection
    ;

// FD のレコード記述項は、その FD のレコード領域を表す
fileSection
    : FILE SECTION PERIOD fileDescriptionEntry*
    ;

// SD は整列作業ファイルである。データセットではなく作業場所を表す
fileDescriptionEntry
    : (FD | SD) IDENTIFIER fileDescriptionClause* PERIOD dataDescriptionEntry*
    ;

fileDescriptionClause
    : BLOCK CONTAINS? NUMBER (TO NUMBER)? (RECORDS | CHARACTER | CHARACTERS)?
    | recordVaryingClause
    | RECORD CONTAINS? NUMBER (TO NUMBER)? (CHARACTER | CHARACTERS)?
    | LABEL (RECORD | RECORDS) (IS | ARE)? (STANDARD | OMITTED)
    | RECORDING MODE? IS? IDENTIFIER
    // DATA RECORD(S) は「このファイルにはこの記述がある」と書くだけの覚え書きである。
    // 実際の記述は FD に続く 01 が持っており、読んで捨てるのが決まりである
    | DATA (RECORD | RECORDS) (IS | ARE)? IDENTIFIER+
    | IS? GLOBAL
    | IS? EXTERNAL
    | linageClause
    // VALUE OF は「ラベルに何を書くか」の指定である。規格でも廃要素であり、
    // ラベルを持たないこちらでは読んで捨てる
    | VALUE OF valueOfEntry+
    ;

valueOfEntry
    : IDENTIFIER IS? (LITERAL | NUMBER | identifier)
    ;

// 1 ページに何行置くか (要件 FR-113)。LINAGE-COUNTER と WRITE ... ADVANCING PAGE が使う
linageClause
    : LINAGE IS? linageCount LINES? linagePart*
    ;

linagePart
    : WITH? FOOTING AT? linageCount
    | LINES? AT? TOP linageCount
    | LINES? AT? BOTTOM linageCount
    ;

linageCount
    : NUMBER
    | identifier
    ;

// 可変長レコードの長さは DEPENDING ON の項目が持つ
recordVaryingClause
    : RECORD IS? VARYING IN? SIZE? (FROM NUMBER)? (TO NUMBER)?
      (CHARACTER | CHARACTERS)? (DEPENDING ON? identifier)?
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
    : (VALUE | VALUES) (IS | ARE)? valueRange valueRange*
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
    : LPAREN subscript subscript* RPAREN
    ;

referenceModifier
    : LPAREN subscript COLON subscript? RPAREN
    ;

subscript
    : NUMBER
    | ALL
    | qualifiedDataName relativeOffset?
    ;

// 相対指定 (要件 FR-025)。演算子は前後に空白を置く決まりなので、
// 「I + 1」は演算子と数字に切れる。「I +1」は符号つきの数字 1 つであり、
// これは相対指定ではなく<b>2 つ目の添字</b>である。切れ目がそのまま意味の違いになる
relativeOffset
    : (PLUS_SIGN | MINUS_SIGN) NUMBER
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
// 宣言部分は手続き部の先頭にあり、通常の流れでは通らない
//
// <b>章が始まったら、あとはすべて章の中である。</b>段落と章を混ぜて並べられる形
// (procedureUnit* のような書き方) にすると、章の中の paragraph* を続けるか抜けるかが
// 外側の繰り返しと区別できず、ANTLR が全文脈の予測に落ちる。CCVS85 の大きな
// プログラムでは<b>それが指数時間になって返ってこなくなった</b>。規格でも
// 手続き部の本体は「段落の並び」か「章の並び」のどちらかであり、混ざらない。
procedureBody
    : declarativesPart? sentence* paragraph* procedureSection*
    ;

declarativesPart
    : DECLARATIVES PERIOD declarativeSection+ END DECLARATIVES PERIOD
    ;

declarativeSection
    : sectionHeader useStatement PERIOD sentence* paragraph*
    ;

// USE は文ではなく、その節がいつ動くかの宣言である
useStatement
    : USE GLOBAL? AFTER? STANDARD? (ERROR | EXCEPTION) PROCEDURE ON? useTarget
    | USE FOR? DEBUGGING ON? debugTarget
    ;

// デバッグの節が何を見張るか (要件 FR-193)
debugTarget
    : ALL PROCEDURES
    | ALL REFERENCES? OF? identifier
    | IDENTIFIER+
    ;

useTarget
    : INPUT
    | OUTPUT
    | I_O
    | EXTEND
    | IDENTIFIER+
    ;

procedureSection
    : sectionHeader sentence* paragraph*
    ;

sectionHeader
    : paragraphName SECTION NUMBER? PERIOD
    ;

paragraph
    : paragraphName PERIOD sentence*
    ;

// 手続き名は<b>数字だけでもよい</b>。データ名と違うところである。
// 段分けの章は「00 SECTION 00.」のように名前も番号も数字で書かれる
paragraphName
    : IDENTIFIER
    | NUMBER
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
    | alterStatement
    | exitStatement
    | callStatement
    | cancelStatement
    | initializeStatement
    | setStatement
    | acceptStatement
    | searchStatement
    | openStatement
    | closeStatement
    | readStatement
    | writeStatement
    | rewriteStatement
    | deleteStatement
    | startStatement
    | sortStatement
    | mergeStatement
    | releaseStatement
    | returnStatement
    | addStatement
    | subtractStatement
    | multiplyStatement
    | divideStatement
    | computeStatement
    ;

moveStatement
    : MOVE (CORRESPONDING | CORR)? moveSource TO identifier identifier*
    ;

moveSource
    : functionCall
    | identifier
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

// 両辺は算術式である。IF 1 + (TWO * 3) = 7 と書ける
relationCondition
    : expression relationalOperator expression
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
      (WHEN OTHER branchBody)?
      END_EVALUATE?
    ;

// 同じ本体に複数の WHEN を並べられる
evaluateBranch
    : (WHEN evaluateObject (ALSO evaluateObject)*)+ branchBody
    ;

// 枝の中身。NEXT SENTENCE は「この文の残りを飛ばす」ことであり、文の並びではない
branchBody
    : NEXT SENTENCE
    | statement*
    ;

evaluateSubject
    : TRUE
    | FALSE
    | arithmeticOperand
    ;

// 範囲は THRU で見分ける。残りは条件を先に試し、当たらなければ値とする
evaluateObject
    : ANY
    | NOT? expression (THRU | THROUGH) expression
    | TRUE
    | FALSE
    | condition
    | NOT? expression
    ;

// DISPLAY は USAGE の DISPLAY と綴りが同じである。文の先頭かどうかで見分ける
displayStatement
    : DISPLAY arithmeticOperand+ (UPON IDENTIFIER)? (WITH? NO ADVANCING)?
    ;

// ---- 入出力文 ----

openStatement
    : OPEN openPhrase+
    ;

openPhrase
    : (INPUT | OUTPUT | I_O | EXTEND) IDENTIFIER+
    ;

closeStatement
    : CLOSE IDENTIFIER+
    ;

// AT END はファイルの終わりに来たときだけ通る
readStatement
    : READ IDENTIFIER NEXT? RECORD? (INTO into=identifier)? (KEY IS? key=identifier)?
      atEndPhrase? notAtEndPhrase? invalidKeyPhrase? notInvalidKeyPhrase? END_READ?
    ;

notAtEndPhrase
    : NOT AT? END statement+
    ;

// INVALID KEY は鍵で引く編成の AT END にあたる
invalidKeyPhrase
    : INVALID KEY? statement+
    ;

notInvalidKeyPhrase
    : NOT INVALID KEY? statement+
    ;

writeStatement
    : WRITE IDENTIFIER (FROM identifier)?
      advancingPhrase?
      invalidKeyPhrase? notInvalidKeyPhrase? END_WRITE?
    ;

// 印字するファイルへの行送り。AFTER は送ってから書き、BEFORE は書いてから送る
advancingPhrase
    : (BEFORE | AFTER) ADVANCING? (advancingLines | PAGE)
    ;

advancingLines
    : (identifier | NUMBER | ZERO | ZEROS | ZEROES) (LINE | LINES)?
    ;

// SORT は溜めて並べ替えて配る。入口と出口はファイルか手続きのどちらかである
sortStatement
    : SORT IDENTIFIER sortKeyClause+ sortDuplicates? sortInput sortOutput
    ;

mergeStatement
    : MERGE IDENTIFIER sortKeyClause+ sortDuplicates? sortUsing sortOutput
    ;

sortKeyClause
    : ON? (ASCENDING | DESCENDING) KEY? identifier+
    ;

sortDuplicates
    : WITH? DUPLICATES (IN ORDER)?
    ;

sortInput
    : sortUsing
    | INPUT PROCEDURE IS? paragraphName ((THRU | THROUGH) paragraphName)?
    ;

sortUsing
    : USING IDENTIFIER+
    ;

sortOutput
    : GIVING IDENTIFIER+
    | OUTPUT PROCEDURE IS? paragraphName ((THRU | THROUGH) paragraphName)?
    ;

// RELEASE は整列作業ファイルへ渡し、RETURN は受け取る
releaseStatement
    : RELEASE IDENTIFIER (FROM identifier)?
    ;

returnStatement
    : RETURN IDENTIFIER RECORD? (INTO identifier)?
      atEndPhrase? notAtEndPhrase? END_RETURN?
    ;

// REWRITE が書き換えるのは、直前に読んだレコードである
rewriteStatement
    : REWRITE IDENTIFIER (FROM identifier)?
      invalidKeyPhrase? notInvalidKeyPhrase? END_REWRITE?
    ;

// DELETE に書くのはファイル名である。消す相手は鍵か、直前に読んだレコードである
deleteStatement
    : DELETE IDENTIFIER RECORD? invalidKeyPhrase? notInvalidKeyPhrase? END_DELETE?
    ;

// START は読まずに位置だけを決める
startStatement
    : START IDENTIFIER (KEY relationalOperator identifier)?
      invalidKeyPhrase? notInvalidKeyPhrase? END_START?
    ;

// SEARCH は表を順に見る。SEARCH ALL は 2 分探索であり、条件の形が限られる
searchStatement
    : SEARCH ALL identifier atEndPhrase? searchWhen+ END_SEARCH?
    | SEARCH identifier (VARYING identifier)? atEndPhrase? searchWhen+ END_SEARCH?
    ;

atEndPhrase
    : AT? END branchBody
    ;

searchWhen
    : WHEN condition branchBody
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
// ALTER は GO TO だけを書いた段落の飛び先を、実行時に書き換える (要件 FR-063)
alterStatement
    : ALTER alterChange+
    ;

alterChange
    : paragraphName TO (PROCEED TO)? paragraphName
    ;

// DEPENDING ON があれば、値が何番目かで飛び先が決まる。無ければ 1 つだけ書ける
goToStatement
    : GO TO? paragraphName+ (DEPENDING ON? identifier)?
    ;

// EXIT は何もしない。PERFORM ... THRU の範囲の終わりに置く段落のためにある。
// EXIT PROGRAM は別物で、呼ばれた側から戻る
exitStatement
    : EXIT PROGRAM?
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
    : functionCall
    | identifier
    | literal
    ;

// 組み込み関数の呼び出し (要件 FR-070)。
//
// 引数の区切りのコンマは飾りであり、SourceTokenSource が落としている。
// 区切っているのは空白のほうである。COBOL では 2 項の演算子は前後に空白を置き、
// 単項の符号は後ろに空白を置かない。したがって字句の切れ目に差が残り、
// 「11, -5」は 2 個、「11 - 5」は 1 個の引数になる。
functionCall
    : FUNCTION functionName (LPAREN expression (COMMA? expression)* RPAREN)?
    ;

// 予約語と綴りが同じ関数名は、ここに並べて拾う
functionName
    : IDENTIFIER
    | RANDOM
    | DATE
    | DAY
    ;

// GIVING がなければ受取項目になるため、ROUNDED を書ける
roundedOperand
    : arithmeticOperand ROUNDED?
    ;

roundedTarget
    : identifier ROUNDED?
    ;
