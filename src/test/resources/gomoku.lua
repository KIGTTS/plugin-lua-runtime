local N = 9
local DIRS = { { 1, 0 }, { 0, 1 }, { 1, 1 }, { 1, -1 } }
local SYM = { [0] = "·", [1] = "●", [2] = "○" }
local DIGIT = { "1", "2", "3", "4", "5", "6", "7", "8", "9" }

local function split(raw, sep)
  local out = {}
  local start = 1
  while true do
    local p = string.find(raw, sep, start, true)
    if not p then
      out[#out + 1] = string.sub(raw, start)
      return out
    end
    out[#out + 1] = string.sub(raw, start, p - 1)
    start = p + #sep
  end
end

local function empty_board()
  local cells = {}
  for i = 1, N * N do cells[i] = "0" end
  return table.concat(cells)
end

local function new_state(mode)
  return {
    board = empty_board(),
    mode = mode or "ai",
    turn = "1",
    pending = "0",
    winner = "",
    last = "",
    history = "",
  }
end

local function encode(st)
  return table.concat({
    st.board, st.mode, st.turn, st.pending, st.winner, st.last, st.history
  }, ";")
end

local function decode(raw)
  if type(raw) ~= "string" or raw == "" then
    return new_state()
  end
  local f = split(raw, ";")
  if #f < 7 then
    return new_state()
  end
  return {
    board = f[1],
    mode = f[2],
    turn = f[3],
    pending = f[4],
    winner = f[5],
    last = f[6],
    history = f[7],
  }
end

local function inb(x, y)
  return x >= 1 and x <= N and y >= 1 and y <= N
end

local function getc(b, x, y)
  local i = (y - 1) * N + x
  return tonumber(string.sub(b, i, i)) or 0
end

local function setc(b, x, y, v)
  local i = (y - 1) * N + x
  return string.sub(b, 1, i - 1) .. tostring(v) .. string.sub(b, i + 1)
end

local function load(ctx)
  return decode(host.data.read("state"))
end

local function save(ctx, st)
  host.data.write("state", encode(st))
end

local function win_at(b, x, y, p)
  for i = 1, #DIRS do
    local dx, dy = DIRS[i][1], DIRS[i][2]
    local count = 1
    local cx, cy = x + dx, y + dy
    while inb(cx, cy) and getc(b, cx, cy) == p do
      count = count + 1
      cx, cy = cx + dx, cy + dy
    end
    cx, cy = x - dx, y - dy
    while inb(cx, cy) and getc(b, cx, cy) == p do
      count = count + 1
      cx, cy = cx - dx, cy - dy
    end
    if count >= 5 then
      return true
    end
  end
  return false
end

local function scan(b, x, y, dx, dy, p)
  local count = 0
  local cx, cy = x + dx, y + dy
  while inb(cx, cy) and getc(b, cx, cy) == p do
    count = count + 1
    cx, cy = cx + dx, cy + dy
  end
  local open = 0
  if inb(cx, cy) and getc(b, cx, cy) == 0 then
    open = 1
  end
  return count, open
end

local function pattern_score(total, open)
  if total >= 5 then
    return 10000000
  elseif total == 4 then
    if open == 2 then return 900000 elseif open == 1 then return 110000 else return 0 end
  elseif total == 3 then
    if open == 2 then return 18000 elseif open == 1 then return 1400 else return 0 end
  elseif total == 2 then
    if open == 2 then return 700 elseif open == 1 then return 90 else return 0 end
  elseif total == 1 then
    if open == 2 then return 35 elseif open == 1 then return 8 else return 0 end
  end
  return 0
end

local function point_score(b, x, y, p)
  local tmp = setc(b, x, y, p)
  local total = 0
  for i = 1, #DIRS do
    local dx, dy = DIRS[i][1], DIRS[i][2]
    local f1, o1 = scan(tmp, x, y, dx, dy, p)
    local f2, o2 = scan(tmp, x, y, -dx, -dy, p)
    total = total + pattern_score(f1 + f2 + 1, o1 + o2)
  end
  return total
end

local function ai_move(st)
  local me = tonumber(st.turn) or 2
  local foe = 3 - me
  local cx, cy = math.ceil(N / 2), math.ceil(N / 2)
  if not (string.find(st.board, "1", 1, true) or string.find(st.board, "2", 1, true)) then
    return cx, cy
  end
  local best_x, best_y, best = cx, cy, -1
  for y = 1, N do
    for x = 1, N do
      if getc(st.board, x, y) == 0 then
        local near = false
        for dy = -2, 2 do
          for dx = -2, 2 do
            if not near and (dx ~= 0 or dy ~= 0) then
              local nx, ny = x + dx, y + dy
              if inb(nx, ny) and getc(st.board, nx, ny) ~= 0 then
                near = true
              end
            end
          end
        end
        if near then
          local atk = point_score(st.board, x, y, me)
          local def = point_score(st.board, x, y, foe)
          local center = (cx - math.abs(x - cx)) + (cy - math.abs(y - cy))
          local score = atk + def * 1.08 + center * 1.5
          if score > best then
            best = score
            best_x, best_y = x, y
          end
        end
      end
    end
  end
  return best_x, best_y
end

local function push_history(st, x, y, p)
  local item = table.concat({ x, ":", y, ":", p })
  if st.history == "" then
    st.history = item
  else
    st.history = st.history .. "," .. item
  end
end

local function place(st, x, y)
  if st.winner ~= "" or not inb(x, y) then
    return false
  end
  local p = tonumber(st.turn) or 1
  if getc(st.board, x, y) ~= 0 then
    return false
  end
  st.board = setc(st.board, x, y, p)
  push_history(st, x, y, p)
  st.last = table.concat({ x, ",", y })
  if win_at(st.board, x, y, p) then
    st.winner = tostring(p)
    return true
  end
  if not string.find(st.board, "0") then
    st.winner = "draw"
    return true
  end
  st.turn = tostring(3 - p)
  return true
end

local function ai_turn(st)
  if st.mode == "ai" and st.winner == "" and st.turn == "2" then
    local x, y = ai_move(st)
    place(st, x, y)
  end
end

local function undo(st)
  if st.history == "" then
    return false
  end
  local items = split(st.history, ",")
  local steps = 1
  if st.mode == "ai" and #items >= 2 then
    steps = 2
  end
  for _ = 1, steps do
    local mv = table.remove(items)
    if mv then
      local parts = split(mv, ":")
      local x, y = tonumber(parts[1]), tonumber(parts[2])
      if x then
        st.board = setc(st.board, tonumber(x), tonumber(y), 0)
      end
    end
  end
  st.winner = ""
  st.pending = "0"
  st.history = table.concat(items, ",")
  local tail = items[#items]
  if tail then
    local p = split(tail, ":")[3]
    st.turn = tostring(3 - (tonumber(p) or 1))
    st.last = ""
  else
    st.turn = "1"
    st.last = ""
  end
  return true
end

local function board_text(st)
  local lines = {}
  local header = "　"
  for x = 1, N do
    header = header .. DIGIT[x]
  end
  lines[#lines + 1] = header
  for y = 1, N do
    local row = DIGIT[y]
    for x = 1, N do
      row = row .. SYM[getc(st.board, x, y)]
    end
    lines[#lines + 1] = row
  end
  return table.concat(lines, "\n")
end

local function status_text(st)
  if st.winner == "draw" then
    return "平局，棋盘已满"
  elseif st.winner ~= "" then
    local who = (st.winner == "1") and "黑棋 ●" or "白棋 ○"
    return who .. " 连成五子，获胜！"
  end
  local who = (st.turn == "1") and "黑棋 ●" or "白棋 ○"
  local pend = tonumber(st.pending) or 0
  if pend > 0 then
    return "轮到 " .. who .. " · 已选第 " .. pend .. " 行，请选列"
  end
  return "轮到 " .. who .. " · 先选行，再选列"
end

local function row_buttons(st)
  local pend = tonumber(st.pending) or 0
  local items = {}
  for i = 1, N do
    local label, id
    if pend > 0 then
      id = "col_" .. i
      label = SYM[getc(st.board, i, pend)] .. DIGIT[i]
    else
      id = "row_" .. i
      label = "第" .. DIGIT[i] .. "行"
    end
    items[#items + 1] = { id = id, label = label, action = id }
  end
  return { type = "actions", actions = items }
end

local function page_blocks(ctx)
  local st = load(ctx)
  local blocks = {}
  blocks[#blocks + 1] = {
    type = "text",
    text = "五子棋 · " .. ((st.mode == "ai") and "人机对战（你执黑 ●）" or "双人对战"),
  }
  blocks[#blocks + 1] = { type = "text", text = status_text(st) }
  blocks[#blocks + 1] = { type = "text", text = board_text(st) }
  blocks[#blocks + 1] = row_buttons(st)

  local controls = {
    { id = "restart", label = "重新开始", action = "restart" },
    { id = "undo", label = "撤销", action = "undo" },
    { id = "mode", label = "切换模式", action = "mode" },
  }
  if (tonumber(st.pending) or 0) > 0 then
    controls[#controls + 1] = { id = "cancel", label = "取消选择", action = "cancel" }
  end
  blocks[#blocks + 1] = { type = "actions", actions = controls }
  return blocks
end

local M = {}

function M.contributions(ctx)
  return { settingsEntries = {{ id = "gomoku", label = "五子棋", pageId = "game", icon = "NONE",
    cardId = "games", cardTitle = "小游戏", summary = "人机 / 双人 · 9×9" }} }
end

function M.pages(ctx)
  return {{ id = "game", title = "五子棋", blocks = page_blocks(ctx) }}
end

function M.execute(action, ctx)
  local id = action
  if type(action) == "table" then
    id = action.id or action.action or action.value or ""
  end
  id = tostring(id or "")

  local st = load(ctx)
  local message

  if id == "restart" then
    st = new_state(st.mode)
    message = "新的一局开始了，黑棋先行"
  elseif id == "mode" then
    st = new_state((st.mode == "ai") and "pvp" or "ai")
    message = "已切换到" .. ((st.mode == "ai") and "人机对战" or "双人对战")
  elseif id == "undo" then
    if undo(st) then
      message = "已撤销一手"
    else
      message = "还没有落子可以撤销"
    end
  elseif id == "cancel" then
    st.pending = "0"
    message = "已取消选择"
  else
    local row = (string.sub(id, 1, 4) == "row_" and tonumber(string.sub(id, 5)))
    local col = (string.sub(id, 1, 4) == "col_" and tonumber(string.sub(id, 5)))
    if row then
      if st.winner ~= "" then
        message = "本局已结束，点「重新开始」开新局"
      else
        st.pending = tostring(row)
        message = "已选第 " .. row .. " 行，请选列"
      end
    elseif col then
      local pend = tonumber(st.pending) or 0
      if pend == 0 then
        message = "请先选择行"
      elseif st.winner ~= "" then
        message = "本局已结束，点「重新开始」开新局"
      elseif getc(st.board, col, pend) ~= 0 then
        message = "这个位置已经有棋子了"
      else
        place(st, col, pend)
        st.pending = "0"
        if st.winner == "" then
          ai_turn(st)
        end
        message = status_text(st)
      end
    else
      message = "未识别的操作：" .. id
    end
  end

  save(ctx, st)
  return { type = "message", text = message }
end

function M.test(ctx)
  local st = new_state()
  assert(#st.board == N * N)
  assert(decode(encode(st)).board == st.board)
  return {ok = true, size = N}
end

return M
