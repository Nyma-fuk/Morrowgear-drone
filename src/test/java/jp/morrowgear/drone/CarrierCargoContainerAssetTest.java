package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CarrierCargoContainerAssetTest {
    @Test void runtimeLodsStayInsideTheApprovedEightContainerBudget() throws Exception {
        assertMesh("carrier_cargo_container",3300);
        assertMesh("carrier_cargo_container_far",1900);
        assertMesh("carrier_missile_container",3300);
        assertMesh("carrier_missile_container_far",1900);
    }
    private static void assertMesh(String name,int triangleLimit) throws Exception {
        Path path=Path.of("src/main/resources/assets/morrowgear_drone/models/runtime/"+name+".mgm");
        try(var in=new DataInputStream(Files.newInputStream(path))){
            assertEquals(0x4D474D34,in.readInt()); int vertices=in.readInt(); assertEquals(2,in.readInt());
            assertEquals(0,vertices%3); assertTrue(vertices/3<=triangleLimit,name+" triangle budget");
        }
    }
}
