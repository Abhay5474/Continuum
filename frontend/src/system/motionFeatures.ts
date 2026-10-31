// Motion's animation, layout and gesture features, in their own chunk: the
// first paint does not wait for them, and nothing animates before it could
// be seen anyway.
import { domMax } from "motion/react";

export default domMax;
