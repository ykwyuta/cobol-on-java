      *-----------------------------------------------------------------
      * PRBWS: work areas for one observation line.
      * Results are shown in hex, so that the code page conversion
      * of SYSOUT retrieval does not mix into the observation.
      *-----------------------------------------------------------------
       01  PRB-CASE            PIC X(24) VALUE SPACES.
       01  PRB-IN              PIC X(256) VALUE SPACES.
       01  PRB-LEN             PIC S9(4) COMP-5.
       01  PRB-OUT             PIC X(512) VALUE SPACES.
       01  PRB-TEXT            PIC X(60) VALUE SPACES.
       01  PRB-LINE            PIC X(640) VALUE SPACES.
       01  PRB-DIGITS          PIC X(16) VALUE "0123456789ABCDEF".
       01  PRB-HALF            PIC 9(4) COMP-5.
       01  PRB-HALF-X REDEFINES PRB-HALF.
           05  PRB-HI          PIC X.
           05  PRB-LO          PIC X.
       01  PRB-I               PIC S9(4) COMP-5.
       01  PRB-Q               PIC S9(4) COMP-5.
       01  PRB-R               PIC S9(4) COMP-5.
       01  PRB-P               PIC S9(4) COMP-5.
