package dev.cobolonjava.cics.bms;

import static dev.cobolonjava.cics.bms.BmsParserTest.card;
import static dev.cobolonjava.cics.bms.BmsParserTest.source;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 記号マップ写し句の形。 */
@Tag("V1")
class BmsSymbolicMapWriterTest {

    @Test
    @DisplayName("組立て済み記号マップで観測した形を、6つの拡張属性で再現する")
    void reproducesObservedLayoutWithSixExtendedAttributes() {
        // 期待値の形は、Bank-of-Zに同梱された組立て済み記号マップ写し句で観測したものである。
        // 名前と長さはこの試験のために変えてある
        String bms = source(
                card("OBSSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
                card("               TIOAPFX=YES,EXTATT=YES,", true),
                card("               DSATTS=(COLOR,PS,HILIGHT,VALIDN,OUTLINE,SOSI)", false),
                card("OBSMP    DFHMDI SIZE=(24,80)", false),
                card("         DFHMDF POS=(1,1),LENGTH=5,INITIAL='TITLE'", false),
                card("COMPANY  DFHMDF POS=(1,10),LENGTH=56,ATTRB=(NORM,PROT)", false),
                card("DUMMY    DFHMDF POS=(24,79),LENGTH=1,ATTRB=(DRK,PROT,FSET)", false),
                card("         DFHMSD TYPE=FINAL", false),
                card("         END", false));

        String expected = """
                       01  OBSMPI.
                           02  FILLER PIC X(12).
                           02  COMPANYL    COMP  PIC  S9(4).
                           02  COMPANYF    PICTURE X.
                           02  FILLER REDEFINES COMPANYF.
                             03 COMPANYA    PICTURE X.
                           02  FILLER   PICTURE X(6).
                           02  COMPANYI  PIC X(56).
                           02  DUMMYL    COMP  PIC  S9(4).
                           02  DUMMYF    PICTURE X.
                           02  FILLER REDEFINES DUMMYF.
                             03 DUMMYA    PICTURE X.
                           02  FILLER   PICTURE X(6).
                           02  DUMMYI  PIC X(1).
                       01  OBSMPO REDEFINES OBSMPI.
                           02  FILLER PIC X(12).
                           02  FILLER PICTURE X(3).
                           02  COMPANYC    PICTURE X.
                           02  COMPANYP    PICTURE X.
                           02  COMPANYH    PICTURE X.
                           02  COMPANYV    PICTURE X.
                           02  COMPANYU    PICTURE X.
                           02  COMPANYM    PICTURE X.
                           02  COMPANYO  PIC X(56).
                           02  FILLER PICTURE X(3).
                           02  DUMMYC    PICTURE X.
                           02  DUMMYP    PICTURE X.
                           02  DUMMYH    PICTURE X.
                           02  DUMMYV    PICTURE X.
                           02  DUMMYU    PICTURE X.
                           02  DUMMYM    PICTURE X.
                           02  DUMMYO  PIC X(1).
                """;

        assertEquals(expected, BmsSymbolicMapWriter.cobol(BmsParser.parse(bms)));
    }

    @Test
    @DisplayName("DSATTSの書き順ではなく、観測した属性byteの順で並べる")
    void ordersAttributeBytesIndependentlyOfDsattsOrder() {
        String bms = source(
                card("ORDSET   DFHMSD TYPE=DSECT,MODE=OUT,LANG=COBOL,TIOAPFX=NO,", true),
                card("               DSATTS=(SOSI,OUTLINE,HILIGHT,COLOR)", false),
                card("ORDMP    DFHMDI SIZE=(24,80)", false),
                card("AMOUNT   DFHMDF POS=(2,1),LENGTH=7,PICOUT='9999.99'", false),
                card("         DFHMSD TYPE=FINAL", false));

        String expected = """
                       01  ORDMPO.
                           02  FILLER PICTURE X(3).
                           02  AMOUNTC    PICTURE X.
                           02  AMOUNTH    PICTURE X.
                           02  AMOUNTU    PICTURE X.
                           02  AMOUNTM    PICTURE X.
                           02  AMOUNTO  PIC 9999.99.
                """;

        assertEquals(expected, BmsSymbolicMapWriter.cobol(BmsParser.parse(bms)));
    }

    @Test
    @DisplayName("OCCURSは添字で参照できる群として並べる")
    void writesOccursAsSubscriptableGroups() {
        String bms = source(
                card("OCCSET   DFHMSD TYPE=DSECT,MODE=INOUT,LANG=COBOL,TIOAPFX=YES,", true),
                card("               DSATTS=(COLOR)", false),
                card("OCCMP    DFHMDI SIZE=(24,80)", false),
                card("ROW      DFHMDF POS=(9,1),LENGTH=79,OCCURS=3", false),
                card("         DFHMSD TYPE=FINAL", false));

        String expected = """
                       01  OCCMPI.
                           02  FILLER PIC X(12).
                           02  DFHMS1 OCCURS 3 TIMES.
                             03 ROWL    COMP  PIC  S9(4).
                             03 ROWF    PICTURE X.
                             03 FILLER REDEFINES ROWF.
                               04 ROWA    PICTURE X.
                             03 FILLER   PICTURE X(1).
                             03 ROWI  PIC X(79).
                       01  OCCMPO REDEFINES OCCMPI.
                           02  FILLER PIC X(12).
                           02  DFHMS2 OCCURS 3 TIMES.
                             03 FILLER PICTURE X(3).
                             03 ROWC    PICTURE X.
                             03 ROWO  PIC X(79).
                """;

        assertEquals(expected, BmsSymbolicMapWriter.cobol(BmsParser.parse(bms)));
    }
}
