import { IBlock, IMatcherResponse } from "@guardian/prosemirror-typerighter/dist/src/ts/interfaces/IMatch";
import { convertTyperighterResponse } from "@guardian/prosemirror-typerighter";

import { v4 } from "uuid";
import { urls } from "../constants";

export const fetchTyperighterMatches = async (
  articleId: string,
  blocks: IBlock[]
): Promise<IMatcherResponse> => {
  const response = await fetch(urls.matches, {
    method: "POST",
    headers: new Headers({
      "Content-Type": "application/json"
    }),
    body: JSON.stringify({
      requestId: `audit-${articleId}-${v4()}`,
      blocks
    })
  });
  const json = await response.json();
  return convertTyperighterResponse(articleId, json);
};
