import eu.darkcube.system.libs.com.google.gson.Gson;
import eu.darkcube.system.libs.com.google.gson.GsonBuilder;
import eu.darkcube.system.libs.net.kyori.adventure.platform.bukkit.BukkitAudiences;
import eu.darkcube.system.libs.net.kyori.adventure.text.Component;

public class Test {
    public static void main(String[] args) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        System.out.println(gson.toJson(new T("asdhl", 14)));
        Component s = Component.empty();
        var x = s.examinableProperties();
        x.forEach(k -> {
        });
    }

    record T(String n, int i) {
    }
}
