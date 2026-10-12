package io.rbgs.api.evidence;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Strict, bounded shape validation and explicit canonical JSON. No ownership inference. */
@Component
public class CaptureJson {
    public static final int MAX_BYTES = 262144;
    private final ObjectMapper json = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(30).maxStringLength(512).maxNumberLength(20).maxTokenCount(10000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public Map<String,Object> parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Evidence exceeds 256 KiB");
        try {
            Object value=json.readValue(bytes,Object.class);
            if (!(value instanceof Map<?,?>)) throw invalid();
            @SuppressWarnings("unchecked") Map<String,Object> object=(Map<String,Object>)value;
            return object;
        } catch (RuntimeException error) { throw invalid(); }
    }
    public String encode(Object value) { return new String(canonical(value),StandardCharsets.UTF_8); }
    public byte[] canonical(Object value) {
        StringBuilder out=new StringBuilder();write(value,out,0);
        byte[] bytes=out.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Evidence exceeds 256 KiB");
        return bytes;
    }
    private void write(Object value,StringBuilder out,int depth) {
        if(depth>30) throw invalid();
        if(value==null) out.append("null");
        else if(value instanceof String s) out.append(json.writeValueAsString(s));
        else if(value instanceof Boolean b) out.append(b);
        else if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {number(value);out.append(value);}
        else if(value instanceof List<?> rows) {out.append('[');boolean first=true;for(Object row:rows){if(!first)out.append(',');first=false;write(row,out,depth+1);}out.append(']');}
        else if(value instanceof Map<?,?> object) {
            TreeMap<String,Object> sorted=new TreeMap<>((a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)));for(var entry:object.entrySet()){if(!(entry.getKey() instanceof String s))throw invalid();sorted.put(s,entry.getValue());}
            out.append('{');boolean first=true;for(var entry:sorted.entrySet()){if(!first)out.append(',');first=false;out.append(json.writeValueAsString(entry.getKey())).append(':');write(entry.getValue(),out,depth+1);}out.append('}');
        } else throw invalid();
    }
    public void evidence(Map<String,Object> root) {
        fields(root,"schemaVersion matchId assignmentId captureId captureRevision assignmentCode client observedCharacter capture","");
        if(number(root.get("schemaVersion"))!=2) throw invalid();
        for(String key:List.of("matchId","assignmentId","captureId")) uuid(root.get(key));
        long revision=number(root.get("captureRevision"));if(revision<1||revision>2147483647)throw invalid();
        String code=text(root.get("assignmentCode"));if(!code.matches("[0-9A-HJKMNP-TV-Z]{16}"))throw invalid();
        Map<String,Object> client=object(root.get("client"));
        fields(client,"addonVersion adapterVersion product gameVersion gameBuild interface companionVersion","");
        for(String key:List.of("addonVersion","adapterVersion","product","gameVersion","gameBuild","companionVersion"))text(client.get(key));
        if(!"wow_classic_beta".equals(client.get("product"))||!"1.60.1".equals(client.get("gameVersion"))||!"70338".equals(client.get("gameBuild"))
                ||number(client.get("interface"))!=16001||!"darkspear-70338-test-1".equals(client.get("adapterVersion")))throw invalid();
        identity(root.get("observedCharacter"));
        Map<String,Object> c=object(root.get("capture"));fields(c,"state stage continuity incompleteReason map startedAt endedAt roster outcome","");
        String state=text(c.get("state")),stage=text(c.get("stage")),continuity=text(c.get("continuity"));
        if(!Set.of("COMPLETE","INCOMPLETE").contains(state)||!Set.of("ARMED","RECORDING","FINAL_PENDING","COMPLETE","INCOMPLETE").contains(stage)
                ||!Set.of("CONTINUOUS","UNKNOWN").contains(continuity))throw invalid();
        for(String key:List.of("startedAt","endedAt"))if(c.get(key)!=null)timestamp(c.get(key));
        if(c.get("map")!=null){var map=object(c.get("map"));fields(map,"profile observedName gameMapId","");
            if(!"DARKSPEAR_DIAGNOSTIC".equals(map.get("profile")))throw invalid();text(map.get("observedName"));if(map.get("gameMapId")!=null)number(map.get("gameMapId"));}
        List<?> roster=rows(c.get("roster"),0,40);
        for(Object item:roster){var row=object(item);fields(row,"character observedTeamKey","killingBlows honorableKills deaths damage healing");identity(row.get("character"));
            if(row.get("observedTeamKey")!=null&&!Set.of("0","1").contains(text(row.get("observedTeamKey"))))throw invalid();
            for(String key:List.of("killingBlows","honorableKills","deaths","damage","healing"))if(row.get(key)!=null)number(row.get(key));}
        var outcome=object(c.get("outcome"));fields(outcome,"kind winningObservedTeamKey","rawWinner");String kind=text(outcome.get("kind"));
        if(outcome.get("rawWinner")!=null)text(outcome.get("rawWinner"));
        if(kind.equals("WIN")){if(!Set.of("0","1").contains(text(outcome.get("winningObservedTeamKey"))))throw invalid();}
        else if(!Set.of("UNKNOWN","DRAW").contains(kind)||outcome.get("winningObservedTeamKey")!=null)throw invalid();
        if(state.equals("COMPLETE")) {
            if(!stage.equals("COMPLETE")||!continuity.equals("CONTINUOUS")||c.get("incompleteReason")!=null||c.get("map")==null
                    ||c.get("startedAt")==null||c.get("endedAt")==null||roster.isEmpty()||kind.equals("UNKNOWN")
                    ||!Long.valueOf(2997).equals(number(object(c.get("map")).get("gameMapId"))))throw invalid();
        } else {
            String reason=text(c.get("incompleteReason"));
            if(Set.of("ARMED","RECORDING","FINAL_PENDING").contains(stage)){if(!reason.equals("CAPTURE_PENDING"))throw invalid();}
            else if(!stage.equals("INCOMPLETE")||!Set.of("MAP_MISMATCH","CHARACTER_CHANGED","EARLY_EXIT","SESSION_BOUNDARY_UNCERTAIN",
                    "MISSING_FINAL_OUTCOME","MISSING_REQUIRED_DATA","UNSUPPORTED_ADAPTER","STORAGE_FULL","CANCELLED").contains(reason))throw invalid();
        }
    }
    public static Map<String,Object> object(Object value) {if(!(value instanceof Map<?,?>))throw invalid();@SuppressWarnings("unchecked")var map=(Map<String,Object>)value;return map;}
    public static void fields(Map<String,Object> value,String required,String optional){Set<String> allowed=new HashSet<>(List.of(required.split(" ")));if(!optional.isEmpty())allowed.addAll(List.of(optional.split(" ")));
        if(!value.keySet().containsAll(List.of(required.split(" ")))||!allowed.containsAll(value.keySet()))throw invalid();}
    public static String text(Object value){if(!(value instanceof String s)||s.isEmpty()||s.codePointCount(0,s.length())>128||s.getBytes(StandardCharsets.UTF_8).length>512||s.codePoints().anyMatch(Character::isISOControl))throw invalid();
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(i+1==s.length()||!Character.isLowSurrogate(s.charAt(++i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}return s;}
    public static long number(Object value){if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long))throw invalid();long n=((Number)value).longValue();if(n<0||n>9007199254740991L)throw invalid();return n;}
    public static UUID uuid(Object value){String s=text(value);if(!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(s);}
    public static Instant timestamp(Object value){try{return Instant.parse(text(value));}catch(RuntimeException error){throw invalid();}}
    public static List<?> rows(Object value,int min,int max){if(!(value instanceof List<?> list)||list.size()<min||list.size()>max)throw invalid();return list;}
    public static void identity(Object value){var identity=object(value);fields(identity,"name realm","guid");text(identity.get("name"));for(String key:List.of("realm","guid"))if(identity.get(key)!=null)text(identity.get(key));}
    public static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    public static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"Invalid or unsupported capture format");}
}
