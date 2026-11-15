package ai.generativearchitects.glow400.cli;

import java.nio.file.Path;

/** Small repositories for the tests of {@code init} and {@code annotate}. */
final class ScaffoldFixtures {

    static final String ORDSTATUS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <pcml version="6.0">
              <program name="ORDSTATUS" path="/QSYS.LIB/DEMOLIB.LIB/ORDSTATUS.PGM">
                <data name="P0ORDN" type="char" length="10" usage="inputoutput"/>
                <data name="P0STAT" type="char" length="1" usage="output"/>
                <data name="P0QTY" type="packed" length="7" precision="2" usage="inputoutput"/>
              </program>
            </pcml>
            """;

    static final String GETBAL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <pcml version="6.0">
              <struct name="LINE"><data name="L" type="char" length="4"/></struct>
              <program name="GETBAL" path="/QSYS.LIB/DEMOLIB.LIB/GETBAL.PGM">
                <data name="ACCT" type="char" length="10" usage="input"/>
                <data name="BAL" type="packed" length="11" precision="2" usage="output"/>
                <data name="DET" type="struct" struct="LINE" usage="inputoutput"/>
                <data name="DAYS" type="int" length="4" usage="inputoutput"/>
              </program>
            </pcml>
            """;

    private ScaffoldFixtures() {
    }

    /** A repository with only PCML: {@code apis/ORDSTATUS} and {@code apis/GETBAL}. */
    static Path pcmlOnly(Path dir) {
        Support.write(dir.resolve("apis/ORDSTATUS/ORDSTATUS.pcml"), ORDSTATUS);
        Support.write(dir.resolve("apis/GETBAL/GETBAL.pcml"), GETBAL);
        return dir;
    }
}
