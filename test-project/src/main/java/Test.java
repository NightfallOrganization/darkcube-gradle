import eu.darkcube.system.libs.test.com.google.gson.Gson;
import eu.darkcube.system.libs.test.com.google.gson.GsonBuilder;
import eu.darkcube.system.libs.test.net.kyori.adventure.text.Component;

void main() {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    System.out.println(gson.toJson(new T("asdhl", 14)));
    Component s = Component.empty();

    var x = s.examinableProperties();
    x.forEach(k -> {
    });
}

record T(String n, int i) {
}
