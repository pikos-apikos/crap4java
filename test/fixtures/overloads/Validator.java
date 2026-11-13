// Equal-distance overload regression for issue #3.

import java.util.Map;
import java.util.UUID;
import java.util.List;

class Validator {











    boolean validate(Map<String, String> values, UUID id) {
        return values != null && id != null;
    }

    boolean validate(Map<String, String> values, UUID id, List<String> fields) {


        return values != null && id != null && fields != null && !fields.isEmpty();
    }
}
