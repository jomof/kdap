use rust_debuggee::*;
use std::env;

fn main() {
    let testcase = env::args().nth(1);
    match testcase.as_deref() {
        Some("stdio") => {
            println!("stdout");
            eprintln!("stderr");
        }
        Some("panic") => {
            panic!("Oops!!!");
        }
        Some("spawn") => {
            let exe = std::env::current_exe().unwrap();
            let mut command = std::process::Command::new(exe);
            command.arg("sleep");
            let mut child = command.spawn().unwrap();
            println!("pid = {}", child.id());
            child.wait().unwrap();
        }
        Some("sleep") => {
            std::thread::sleep(std::time::Duration::from_secs(10));
        }
        Some(_) => {
            primitives();
            enums();
            structs();
            arrays();
            boxes();
            strings();
            maps();
            misc();
            step_in();
        }
        None => {
            println!("No testcase was specified.");
            std::process::exit(-1);
        }
    }
}
