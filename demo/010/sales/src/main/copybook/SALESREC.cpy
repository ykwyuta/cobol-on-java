      *> 売上ファイルの 1 レコード。GEN-SALES と PRT-SALES が同じ形を使う
       01  SALES-REC.
           05  SR-DATE       PIC X(6).
           05  SR-ITEM-ID    PIC 9(4).
           05  SR-ITEM-NAME  PIC X(12).
           05  SR-PRICE      PIC 9(6).
           05  SR-QTY        PIC 9(4).
