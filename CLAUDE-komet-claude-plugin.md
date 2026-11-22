# komet-claude-plugin — Project Notes

<!-- This file is for hand-authored, project-specific information.
     It is created by ws:scaffold-init but never overwritten.
     Commit this file to git. -->

## Architecture

### A narration is the assistant's commit (IKE-Network/ike-issues#1399)

A narration of a stamped document's git commit is the assistant's own
work (decision 4). When the IKE Dashboard's file of authors
(`~/.ike/authors.properties`, read by `CommitAuthors` in
komet-stamped-document-plugin) names the person's assistant, the
annotation commit has the assistant as git author and the person as
committer. Its annotation then names no annotator of its own, so in the
store the comment's author is the concept made from the assistant's
address. With no assistant named, the commit is git's own and names
`Komet Narrator (Claude)`, as before. Narrations the narrator writes in the
store, on knowledge commits, are still `Komet Narrator (Claude)`'s.

A document the assistant makes with `create_document` is the assistant's
commit too.

## Key Classes

- `CommitNarrator.writeNarration`: the annotation commit of a document
  narration.

## Testing Notes

- `NarrationCommitTest` writes narrations into a scratch repository with
  and without an assistant; the names are invented.
