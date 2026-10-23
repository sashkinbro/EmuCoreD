// SPDX-License-Identifier: GPL-3.0-or-later
// Compiled in the upstream State translation unit, whose original GPL notice is retained.
namespace State {
bool EmuCoreDSaveCompleted(Core::System& system, const std::string& filename)
{
  const Core::CPUThreadGuard guard(system);
  // A unique destination prevents a previous successful file from masking failure.
  const std::string staging = filename + ".emucored-pending";
  File::Delete(staging);
  SaveAsFromCore(system, staging);
  s_compress_and_dump_thread.WaitForCompletion();
  if (!File::Exists(staging) || File::GetSize(staging) <= STATE_HEADER_SIZE)
    return false;
  return File::Rename(staging, filename);
}
bool EmuCoreDLoadCompleted(Core::System& system, const std::string& filename)
{
  const Core::CPUThreadGuard guard(system);
  if (!CheckIfStateLoadIsAllowed(system))
    return false;
  return LoadAsFromCore(system, filename);
}
}
